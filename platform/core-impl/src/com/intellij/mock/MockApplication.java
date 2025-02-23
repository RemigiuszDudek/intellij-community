// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.mock;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.*;
import com.intellij.openapi.application.ex.ApplicationEx;
import com.intellij.openapi.application.impl.AnyModalityState;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.ThrowableComputable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;
import org.jetbrains.ide.PooledThreadExecutor;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Modifier;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

public class MockApplication extends MockComponentManager implements ApplicationEx {
  public static int INSTANCES_CREATED = 0;

  public MockApplication(@NotNull Disposable parentDisposable) {
    super(null, parentDisposable);

    INSTANCES_CREATED++;
    //noinspection TestOnlyProblems
    Extensions.setRootArea(getExtensionArea(), parentDisposable);
  }

  @NotNull
  @TestOnly
  public static MockApplication setUp(@NotNull Disposable parentDisposable) {
    MockApplication app = new MockApplication(parentDisposable);
    ApplicationManager.setApplication(app, parentDisposable);
    return app;
  }

  @Override
  public <T> T getService(@NotNull Class<T> serviceClass, boolean createIfNeeded) {
    T service = super.getService(serviceClass, createIfNeeded);
    if (service == null && createIfNeeded && Modifier.isFinal(serviceClass.getModifiers()) && serviceClass.isAnnotationPresent(Service.class)) {
      //noinspection SynchronizeOnThis,SynchronizationOnLocalVariableOrMethodParameter
      synchronized (serviceClass) {
        service = super.getService(serviceClass, true);
        if (service != null) {
          return service;
        }

        getPicoContainer().registerComponentImplementation(serviceClass.getName(), serviceClass);
        return super.getService(serviceClass, true);
      }
    }
    return service;
  }

  @Override
  public boolean isInternal() {
    return false;
  }

  @Override
  public boolean isEAP() {
    return false;
  }

  @Override
  public boolean isDispatchThread() {
    return SwingUtilities.isEventDispatchThread();
  }

  @Override
  public boolean isActive() {
    return true;
  }

  @Override
  public void assertReadAccessAllowed() {
  }

  @Override
  public void assertWriteAccessAllowed() {
  }

  @Override
  public void assertIsDispatchThread() {
  }

  @Override
  public boolean isReadAccessAllowed() {
    return true;
  }

  @Override
  public boolean isWriteAccessAllowed() {
    return true;
  }

  @Override
  public boolean isUnitTestMode() {
    return true;
  }

  @Override
  public boolean isHeadlessEnvironment() {
    return true;
  }

  @Override
  public boolean isCommandLine() {
    return true;
  }

  @NotNull
  @Override
  public Future<?> executeOnPooledThread(@NotNull Runnable action) {
    return PooledThreadExecutor.INSTANCE.submit(action);
  }

  @NotNull
  @Override
  public <T> Future<T> executeOnPooledThread(@NotNull Callable<T> action) {
    return PooledThreadExecutor.INSTANCE.submit(action);
  }

  @Override
  public boolean isDisposeInProgress() {
    return false;
  }

  @Override
  public boolean isRestartCapable() {
    return false;
  }

  @Override
  public void restart() {
  }

  @Override
  public void runReadAction(@NotNull Runnable action) {
    action.run();
  }

  @Override
  public <T> T runReadAction(@NotNull Computable<T> computation) {
    return computation.compute();
  }

  @Override
  public <T, E extends Throwable> T runReadAction(@NotNull ThrowableComputable<T, E> computation) throws E {
    return computation.compute();
  }

  @Override
  public void runWriteAction(@NotNull Runnable action) {
    action.run();
  }

  @Override
  public <T> T runWriteAction(@NotNull Computable<T> computation) {
    return computation.compute();
  }

  @Override
  public <T, E extends Throwable> T runWriteAction(@NotNull ThrowableComputable<T, E> computation) throws E {
    return computation.compute();
  }

  @NotNull
  @Override
  public AccessToken acquireReadActionLock() {
    return AccessToken.EMPTY_ACCESS_TOKEN;
  }

  @NotNull
  @Override
  public AccessToken acquireWriteActionLock(@Nullable Class marker) {
    return AccessToken.EMPTY_ACCESS_TOKEN;
  }

  @Override
  public boolean hasWriteAction(@NotNull Class<?> actionClass) {
    return false;
  }

  @Override
  public void addApplicationListener(@NotNull ApplicationListener listener) {
  }

  @Override
  public void addApplicationListener(@NotNull ApplicationListener listener, @NotNull Disposable parent) {
  }

  @Override
  public void removeApplicationListener(@NotNull ApplicationListener listener) {
  }

  @Override
  public long getStartTime() {
    return 0;
  }

  @Override
  public long getIdleTime() {
    return 0;
  }

  @NotNull
  @Override
  public ModalityState getNoneModalityState() {
    return ModalityState.NON_MODAL;
  }

  @Override
  public void invokeLater(@NotNull final Runnable runnable, @NotNull final Condition expired) {
  }

  @Override
  public void invokeLater(@NotNull final Runnable runnable, @NotNull final ModalityState state, @NotNull final Condition expired) {
  }

  @Override
  public void invokeLater(@NotNull Runnable runnable) {
  }

  @Override
  public void invokeLater(@NotNull Runnable runnable, @NotNull ModalityState state) {
  }

  @Override
  @NotNull
  public ModalityInvokator getInvokator() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void invokeAndWait(@NotNull Runnable runnable, @NotNull ModalityState modalityState) {
  }

  @Override
  public void invokeAndWait(@NotNull Runnable runnable) throws ProcessCanceledException {
    invokeAndWait(runnable, getDefaultModalityState());
  }

  @NotNull
  @Override
  public ModalityState getCurrentModalityState() {
    return getNoneModalityState();
  }

  @NotNull
  @Override
  public ModalityState getAnyModalityState() {
    return AnyModalityState.ANY;
  }

  @NotNull
  @Override
  public ModalityState getModalityStateForComponent(@NotNull Component c) {
    return getNoneModalityState();
  }

  @NotNull
  @Override
  public ModalityState getDefaultModalityState() {
    return getNoneModalityState();
  }

  @Override
  public void exit() {
  }

  @Override
  public void saveAll() {
  }

  @Override
  public void saveSettings() {
  }

  @Override
  public boolean holdsReadLock() {
    return false;
  }

  @Override
  public void load(@Nullable String path) {
  }

  @Override
  public void exit(boolean force, boolean exitConfirmed) {
  }

  @Override
  public void restart(boolean exitConfirmed) {
  }

  @Override
  public void restart(boolean exitConfirmed, boolean elevate) {
  }

  @Override
  public boolean runProcessWithProgressSynchronously(@NotNull final Runnable process,
                                                     @NotNull final String progressTitle,
                                                     final boolean canBeCanceled,
                                                     @Nullable final Project project,
                                                     final JComponent parentComponent) {
    return false;
  }

  @Override
  public boolean runProcessWithProgressSynchronously(@NotNull Runnable process,
                                                     @NotNull String progressTitle,
                                                     boolean canBeCanceled,
                                                     @Nullable Project project,
                                                     JComponent parentComponent,
                                                     String cancelText) {
    return false;
  }

  @Override
  public boolean runProcessWithProgressSynchronously(@NotNull Runnable process,
                                                     @NotNull String progressTitle,
                                                     boolean canBeCanceled,
                                                     Project project) {
    return false;
  }

  @Override
  public boolean runProcessWithProgressSynchronouslyInReadAction(@Nullable Project project,
                                                                 @NotNull String progressTitle,
                                                                 boolean canBeCanceled,
                                                                 String cancelText,
                                                                 JComponent parentComponent,
                                                                 @NotNull Runnable process) {
    return false;
  }

  @Override
  public void assertIsDispatchThread(@Nullable final JComponent component) {
  }

  @Override
  public void assertTimeConsuming() {
  }

  @Override
  public boolean tryRunReadAction(@NotNull Runnable runnable) {
    runReadAction(runnable);
    return true;
  }

  @Override
  public boolean isWriteActionInProgress() {
    return false;
  }

  @Override
  public boolean isWriteActionPending() {
    return false;
  }

  @Override
  public boolean isSaveAllowed() {
    return true;
  }

  @Override
  public void setSaveAllowed(boolean value) {
  }
}
