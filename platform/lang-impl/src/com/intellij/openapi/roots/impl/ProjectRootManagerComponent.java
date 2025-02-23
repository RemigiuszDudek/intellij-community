// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.roots.impl;

import com.intellij.ProjectTopics;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.*;
import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.components.impl.stores.BatchUpdateListener;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileTypes.FileTypeEvent;
import com.intellij.openapi.fileTypes.FileTypeListener;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.impl.ModuleEx;
import com.intellij.openapi.project.DumbModeTask;
import com.intellij.openapi.project.DumbServiceImpl;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.WatchedRootsProvider;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.vfs.impl.VirtualFilePointerContainerImpl;
import com.intellij.openapi.vfs.newvfs.NewVirtualFile;
import com.intellij.openapi.vfs.pointers.VirtualFilePointer;
import com.intellij.openapi.vfs.pointers.VirtualFilePointerContainer;
import com.intellij.openapi.vfs.pointers.VirtualFilePointerListener;
import com.intellij.openapi.vfs.pointers.VirtualFilePointerManager;
import com.intellij.project.ProjectKt;
import com.intellij.ui.GuiUtils;
import com.intellij.util.ConcurrencyUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.indexing.FileBasedIndex;
import com.intellij.util.indexing.FileBasedIndexImpl;
import com.intellij.util.indexing.FileBasedIndexProjectHandler;
import com.intellij.util.indexing.UnindexedFilesUpdater;
import com.intellij.util.messages.MessageBusConnection;
import gnu.trove.THashSet;
import org.jetbrains.annotations.CalledInAwt;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * ProjectRootManager extended with ability to watch events.
 */
public class ProjectRootManagerComponent extends ProjectRootManagerImpl implements ProjectComponent, Disposable {
  private static final Logger LOG = Logger.getInstance(ProjectRootManagerComponent.class);
  private static final boolean LOG_CACHES_UPDATE =
    ApplicationManager.getApplication().isInternal() && !ApplicationManager.getApplication().isUnitTestMode();

  private final ExecutorService myExecutor = AppExecutorUtil.createBoundedApplicationPoolExecutor("Project Root Manager", 1);
  private Future<?> myCollectWatchRootsFuture = CompletableFuture.completedFuture(null);

  private boolean myPointerChangesDetected;
  private int myInsideRefresh;
  @NotNull
  private Set<LocalFileSystem.WatchRequest> myRootsToWatch = new THashSet<>();
  private Disposable myRootPointersDisposable = Disposer.newDisposable(); // accessed in EDT

  public ProjectRootManagerComponent(@NotNull Project project) {
    super(project);

    MessageBusConnection connection = project.getMessageBus().connect(this);
    connection.subscribe(FileTypeManager.TOPIC, new FileTypeListener() {
      @Override
      public void beforeFileTypesChanged(@NotNull FileTypeEvent event) {
        beforeRootsChange(true);
      }

      @Override
      public void fileTypesChanged(@NotNull FileTypeEvent event) {
        rootsChanged(true);
      }
    });

    VirtualFileManager.getInstance().addVirtualFileManagerListener(new VirtualFileManagerListener() {
      @Override
      public void afterRefreshFinish(boolean asynchronous) {
        doUpdateOnRefresh();
      }
    }, project);

    if (!myProject.isDefault()) {
      StartupManager.getInstance(project).registerStartupActivity(() -> myStartupActivityPerformed = true);
    }

    connection.subscribe(BatchUpdateListener.TOPIC, new BatchUpdateListener() {
      @Override
      public void onBatchUpdateStarted() {
        myRootsChanged.levelUp();
        myFileTypesChanged.levelUp();
      }

      @Override
      public void onBatchUpdateFinished() {
        myRootsChanged.levelDown();
        myFileTypesChanged.levelDown();
      }
    });
  }

  @Override
  public void projectOpened() {
    addRootsToWatch();
    ApplicationManager.getApplication().addApplicationListener(new AppListener(), myProject);
  }

  @Override
  public void projectClosed() {
    LocalFileSystem.getInstance().removeWatchedRoots(myRootsToWatch);
  }

  @CalledInAwt
  private void addRootsToWatch() {
    if (myProject.isDefault()) return;

    Disposable prev = myRootPointersDisposable;
    Disposable next = Disposer.newDisposable();

    Application application = ApplicationManager.getApplication();
    ExecutorService service = application.isUnitTestMode() ? ConcurrencyUtil.newSameThreadExecutorService() : myExecutor;

    myCollectWatchRootsFuture.cancel(false);
    myCollectWatchRootsFuture = service.submit(() -> {
      if (myProject.isDisposed()) return;
      Pair<Set<String>, Set<String>> pair = ReadAction.compute(() -> collectWatchRoots(next));
      GuiUtils.invokeLaterIfNeeded(() -> {
        myRootPointersDisposable = next;
        Disposer.dispose(prev); // dispose after the re-creating container to keep VFPs from disposing and re-creating back
        myRootsToWatch = LocalFileSystem.getInstance().replaceWatchedRoots(myRootsToWatch, pair.first, pair.second);
      }, ModalityState.any());
    });
  }

  private void beforeRootsChange(boolean fileTypes) {
    if (myProject.isDisposed()) return;
    getBatchSession(fileTypes).beforeRootsChanged();
  }

  private void rootsChanged(boolean fileTypes) {
    getBatchSession(fileTypes).rootsChanged();
  }

  private void doUpdateOnRefresh() {
    if (ApplicationManager.getApplication().isUnitTestMode() && (!myStartupActivityPerformed || myProject.isDisposed())) {
      return; // in test mode suppress addition to a queue unless project is properly initialized
    }
    if (myProject.isDefault()) {
      return;
    }

    if (LOG_CACHES_UPDATE || LOG.isDebugEnabled()) {
      LOG.debug("refresh");
    }
    DumbServiceImpl dumbService = DumbServiceImpl.getInstance(myProject);
    DumbModeTask task = FileBasedIndexProjectHandler.createChangedFilesIndexingTask(myProject);
    if (task != null) {
      dumbService.queueTask(task);
    }
  }

  @Override
  protected void fireBeforeRootsChangeEvent(boolean fileTypes) {
    isFiringEvent = true;
    try {
      myProject.getMessageBus().syncPublisher(ProjectTopics.PROJECT_ROOTS).beforeRootsChange(new ModuleRootEventImpl(myProject, fileTypes));
    }
    finally {
      isFiringEvent = false;
    }
  }

  @Override
  protected void fireRootsChangedEvent(boolean fileTypes) {
    isFiringEvent = true;
    try {
      myProject.getMessageBus().syncPublisher(ProjectTopics.PROJECT_ROOTS).rootsChanged(new ModuleRootEventImpl(myProject, fileTypes));
    }
    finally {
      isFiringEvent = false;
    }

    synchronizeRoots();
    addRootsToWatch();
  }

  private Pair<Set<String>, Set<String>> collectWatchRoots(@NotNull Disposable disposable) {
    Set<String> recursivePaths = new THashSet<>(FileUtil.PATH_HASHING_STRATEGY);
    Set<String> flatPaths = new THashSet<>(FileUtil.PATH_HASHING_STRATEGY);

    String projectFilePath = myProject.getProjectFilePath();
    if (projectFilePath != null && !Project.DIRECTORY_STORE_FOLDER.equals(new File(projectFilePath).getParentFile().getName())) {
      flatPaths.add(FileUtil.toSystemIndependentName(projectFilePath));
      String wsFilePath = ProjectKt.getStateStore(myProject).getWorkspaceFilePath();  // may not exist yet
      if (wsFilePath != null) {
        flatPaths.add(FileUtil.toSystemIndependentName(wsFilePath));
      }
    }

    for (AdditionalLibraryRootsProvider extension : AdditionalLibraryRootsProvider.EP_NAME.getExtensions()) {
      Collection<VirtualFile> toWatch = extension.getRootsToWatch(myProject);
      if (!toWatch.isEmpty()) {
        recursivePaths.addAll(ContainerUtil.map(toWatch, VirtualFile::getPath));
      }
    }

    for (WatchedRootsProvider extension : WatchedRootsProvider.EP_NAME.getExtensions(myProject)) {
      Set<String> toWatch = extension.getRootsToWatch();
      if (!toWatch.isEmpty()) {
        recursivePaths.addAll(ContainerUtil.map(toWatch, FileUtil::toSystemIndependentName));
      }
    }


    List<String> recursiveUrls = ContainerUtil.map(recursivePaths, VfsUtilCore::pathToUrl);
    Set<String> excludedUrls = new THashSet<>();
    // changes in files provided by this method should be watched manually because no-one's bothered to set up correct pointers for them
    for (DirectoryIndexExcludePolicy excludePolicy : DirectoryIndexExcludePolicy.EP_NAME.getExtensions(myProject)) {
      Collections.addAll(excludedUrls, excludePolicy.getExcludeUrlsForProject());
    }

    // avoid creating empty unnecessary container
    if (!recursiveUrls.isEmpty() || !flatPaths.isEmpty() || !excludedUrls.isEmpty()) {
      Disposer.register(this, disposable);
      // creating a container with these URLs with the sole purpose to get events to getRootsValidityChangedListener() when these roots change
      VirtualFilePointerContainer container =
        VirtualFilePointerManager.getInstance().createContainer(disposable, getRootsValidityChangedListener());

      ((VirtualFilePointerContainerImpl)container).addAllJarDirectories(recursiveUrls, true);
      flatPaths.forEach(path -> container.add(VfsUtilCore.pathToUrl(path)));
      ((VirtualFilePointerContainerImpl)container).addAll(excludedUrls);
    }

    // module roots already fire validity change events, see usages of ProjectRootManagerComponent.getRootsValidityChangedListener
    collectModuleWatchRoots(recursivePaths, flatPaths);

    return Pair.create(recursivePaths, flatPaths);
  }

  private void collectModuleWatchRoots(@NotNull Set<String> recursivePaths, @NotNull Set<String> flatPaths) {
    Set<String> urls = ContainerUtil.newTroveSet(FileUtil.PATH_HASHING_STRATEGY);

    for (Module module : ModuleManager.getInstance(myProject).getModules()) {
      ModuleRootManager rootManager = ModuleRootManager.getInstance(module);

      ContainerUtil.addAll(urls, rootManager.getContentRootUrls());

      rootManager.orderEntries().withoutModuleSourceEntries().withoutDepModules().forEach(entry -> {
        for (OrderRootType type : OrderRootType.getAllTypes()) {
          ContainerUtil.addAll(urls, entry.getUrls(type));
        }
        return true;
      });
    }

    for (String url : urls) {
      String protocol = VirtualFileManager.extractProtocol(url);
      if (protocol == null || StandardFileSystems.FILE_PROTOCOL.equals(protocol)) {
        recursivePaths.add(extractLocalPath(url));
      }
      else if (StandardFileSystems.JAR_PROTOCOL.equals(protocol)) {
        flatPaths.add(extractLocalPath(url));
      }
      else if (StandardFileSystems.JRT_PROTOCOL.equals(protocol)) {
        recursivePaths.add(extractLocalPath(url));
      }
    }
  }

  private void synchronizeRoots() {
    if (!myStartupActivityPerformed) return;

    if (LOG_CACHES_UPDATE || LOG.isDebugEnabled()) {
      LOG.debug(new Throwable("sync roots"));
    }
    else if (!ApplicationManager.getApplication().isUnitTestMode()) {
      LOG.info("project roots have changed");
    }

    DumbServiceImpl dumbService = DumbServiceImpl.getInstance(myProject);
    if (FileBasedIndex.getInstance() instanceof FileBasedIndexImpl) {
      dumbService.queueTask(new UnindexedFilesUpdater(myProject));
    }
  }

  @Override
  protected void clearScopesCaches() {
    super.clearScopesCaches();

    LibraryScopeCache libraryScopeCache = myProject.getServiceIfCreated(LibraryScopeCache.class);
    if (libraryScopeCache != null) {
      libraryScopeCache.clear();
    }
  }

  @Override
  public void clearScopesCachesForModules() {
    super.clearScopesCachesForModules();
    Module[] modules = ModuleManager.getInstance(myProject).getModules();
    for (Module module : modules) {
      ((ModuleEx)module).clearScopesCache();
    }
  }

  @Override
  public void markRootsForRefresh() {
    Set<String> paths = new THashSet<>(FileUtil.PATH_HASHING_STRATEGY);
    collectModuleWatchRoots(paths, paths);

    LocalFileSystem fs = LocalFileSystem.getInstance();
    for (String path : paths) {
      VirtualFile root = fs.findFileByPath(path);
      if (root instanceof NewVirtualFile) {
        ((NewVirtualFile)root).markDirtyRecursively();
      }
    }
  }

  @Override
  public void dispose() {
    myCollectWatchRootsFuture.cancel(false);
    myExecutor.shutdownNow();
    assertListenersAreDisposed();
  }

  private class AppListener implements ApplicationListener {
    @Override
    public void beforeWriteActionStart(@NotNull Object action) {
      myInsideRefresh++;
    }

    @Override
    public void writeActionFinished(@NotNull Object action) {
      if (--myInsideRefresh == 0 && myPointerChangesDetected) {
        myPointerChangesDetected = false;
        incModificationCount();

        myProject.getMessageBus().syncPublisher(ProjectTopics.PROJECT_ROOTS).rootsChanged(new ModuleRootEventImpl(myProject, false));

        synchronizeRoots();
        addRootsToWatch();
      }
    }
  }

  private final VirtualFilePointerListener myRootsChangedListener = new VirtualFilePointerListener() {
    @Override
    public void beforeValidityChanged(@NotNull VirtualFilePointer[] pointers) {
      if (myProject.isDisposed()) {
        return;
      }

      if (myInsideRefresh == 0) {
        beforeRootsChange(false);
        if (LOG_CACHES_UPDATE || LOG.isDebugEnabled()) {
          LOG.debug(new Throwable(pointers.length > 0 ? pointers[0].getPresentableUrl():""));
        }
      }
      else if (!myPointerChangesDetected) {
        //this is the first pointer changing validity
        myPointerChangesDetected = true;
        myProject.getMessageBus().syncPublisher(ProjectTopics.PROJECT_ROOTS).beforeRootsChange(new ModuleRootEventImpl(myProject, false));
        if (LOG_CACHES_UPDATE || LOG.isDebugEnabled()) {
          LOG.debug(new Throwable(pointers.length > 0 ? pointers[0].getPresentableUrl() : ""));
        }
      }
    }

    @Override
    public void validityChanged(@NotNull VirtualFilePointer[] pointers) {
      if (myProject.isDisposed()) {
        return;
      }

      if (myInsideRefresh > 0) {
        clearScopesCaches();
      }
      else {
        rootsChanged(false);
      }
    }
  };

  @NotNull
  @Override
  public VirtualFilePointerListener getRootsValidityChangedListener() {
    return myRootsChangedListener;
  }
}