// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.wm.impl;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.TaskInfo;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.ui.popup.BalloonHandler;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.WindowManagerListener;
import com.intellij.openapi.wm.ex.ProgressIndicatorEx;
import com.intellij.openapi.wm.ex.StatusBarEx;
import com.intellij.openapi.wm.ex.WindowManagerEx;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.HyperlinkListener;
import java.awt.*;
import java.awt.event.ComponentEvent;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Anton Katilin
 * @author Vladimir Kondratyev
 */
public final class TestWindowManager extends WindowManagerEx {
  private static final Key<StatusBar> STATUS_BAR = Key.create("STATUS_BAR");
  private final DesktopLayout myLayout = new DesktopLayout();

  @Override
  public final void doNotSuggestAsParent(final Window window) { }

  @Override
  public final Window suggestParentWindow(@Nullable final Project project) {
    return null;
  }

  @Override
  public final StatusBar getStatusBar(@NotNull Project project) {
    synchronized (STATUS_BAR) {
      StatusBar statusBar = project.getUserData(STATUS_BAR);
      if (statusBar == null) {
        project.putUserData(STATUS_BAR, statusBar = new DummyStatusBar());
      }
      return statusBar;
    }
  }

  @Override
  public IdeFrame getIdeFrame(final Project project) {
    return null;
  }

  @Nullable
  @Override
  public ProjectFrameHelper findFrameHelper(@Nullable Project project) {
    return null;
  }

  @Nullable
  @Override
  public ProjectFrameHelper getFrameHelper(@Nullable Project project) {
    return null;
  }

  @Override
  public Rectangle getScreenBounds(@NotNull Project project) {
    return null;
  }

  @Override
  public void setWindowMask(final Window window, final Shape mask) { }

  @Override
  public void resetWindow(final Window window) { }

  @Override
  @NotNull
  public ProjectFrameHelper[] getAllProjectFrames() {
    return new ProjectFrameHelper[0];
  }

  @Override
  public JFrame findVisibleFrame() {
    return null;
  }

  @Nullable
  @Override
  public final IdeFrameImpl getFrame(final Project project) {
    return null;
  }

  @Override
  @NotNull
  public final ProjectFrameHelper allocateFrame(@NotNull Project project) {
    ProjectFrameHelper frame = new ProjectFrameHelper();
    frame.preInit(null);
    frame.init();
    return frame;
  }

  @Override
  public final Component getFocusedComponent(@NotNull final Window window) {
    throw new UnsupportedOperationException();
  }

  @Override
  public final Component getFocusedComponent(final Project project) {
    return null;
  }

  @Override
  public final Window getMostRecentFocusedWindow() {
    return null;
  }

  @Override
  public IdeFrame findFrameFor(@Nullable Project project) {
    return null;
  }

  @NotNull
  @Override
  public final CommandProcessor getCommandProcessor() {
    throw new UnsupportedOperationException();
  }

  @Override
  public final DesktopLayout getLayout() {
    return myLayout;
  }

  @Override
  public final void setLayout(final DesktopLayout layout) {
    throw new UnsupportedOperationException();
  }

  @Override
  public final void dispatchComponentEvent(final ComponentEvent e) {
    throw new UnsupportedOperationException();
  }

  @Override
  public final Rectangle getScreenBounds() {
    throw new UnsupportedOperationException();
  }

  @Override
  public final boolean isInsideScreenBounds(final int x, final int y, final int width) {
    throw new UnsupportedOperationException();
  }

  @Override
  public final boolean isAlphaModeSupported() {
    return false;
  }

  @Override
  public final void setAlphaModeRatio(final Window window, final float ratio) {
    throw new UnsupportedOperationException();
  }

  @Override
  public final boolean isAlphaModeEnabled(final Window window) {
    throw new UnsupportedOperationException();
  }

  @Override
  public final void setAlphaModeEnabled(final Window window, final boolean state) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setWindowShadow(Window window, WindowShadowMode mode) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void hideDialog(JDialog dialog, Project project) {
    dialog.dispose();
  }

  @Override
  public void adjustContainerWindow(Component c, Dimension oldSize, Dimension newSize) { }

  @Override
  public void addListener(final WindowManagerListener listener) { }

  @Override
  public void removeListener(final WindowManagerListener listener) { }

  @Override
  public boolean isFullScreenSupportedInCurrentOS() {
    return false;
  }

  private static final class DummyStatusBar implements StatusBarEx {
    private final Map<String, StatusBarWidget> myWidgetMap = new HashMap<>();

    @Nullable
    @Override
    public Project getProject() {
      return null;
    }

    @Override
    public Dimension getSize() {
      return new Dimension(0, 0);
    }

    @Nullable
    @Override
    public StatusBar createChild(@NotNull IdeFrame frame) {
      return null;
    }

    @Override
    public IdeFrame getFrame() {
      return null;
    }

    @Override
    public StatusBar findChild(Component c) {
      return null;
    }

    @Override
    public void setInfo(@Nullable String s, @Nullable String requestor) { }

    @Override
    public boolean isVisible() {
      return false;
    }

    @Override
    public void addCustomIndicationComponent(@NotNull JComponent c) { }

    @Override
    public void removeCustomIndicationComponent(@NotNull JComponent c) { }

    @Override
    public void addProgress(@NotNull ProgressIndicatorEx indicator, @NotNull TaskInfo info) { }

    @Override
    public List<Pair<TaskInfo, ProgressIndicator>> getBackgroundProcesses() {
      return Collections.emptyList();
    }

    @Override
    public void addWidget(@NotNull StatusBarWidget widget) {
      myWidgetMap.put(widget.ID(), widget);
    }

    @Override
    public void addWidget(@NotNull StatusBarWidget widget, @NotNull String anchor) {
      addWidget(widget);
    }

    @Override
    public void addWidget(@NotNull StatusBarWidget widget, @NotNull Disposable parentDisposable) {
      Disposer.register(parentDisposable, widget);
      addWidget(widget);
    }

    @Override
    public void addWidget(@NotNull StatusBarWidget widget, @NotNull String anchor, @NotNull Disposable parentDisposable) {
      Disposer.register(parentDisposable, widget);
      addWidget(widget);
    }

    @Override
    public void updateWidgets() { }

    @Override
    public void dispose() { }

    @Override
    public void updateWidget(@NotNull String id) { }

    @Override
    public StatusBarWidget getWidget(String id) {
      return myWidgetMap.get(id);
    }

    @Override
    public void removeWidget(@NotNull String id) { }

    @Override
    public void fireNotificationPopup(@NotNull JComponent content, final Color backgroundColor) { }

    @Override
    public JComponent getComponent() {
      return null;
    }

    @Override
    public final String getInfo() {
      return null;
    }

    @Override
    public final void setInfo(final String s) {}

    @Override
    public void startRefreshIndication(final String tooltipText) { }

    @Override
    public void stopRefreshIndication() { }

    @Override
    public boolean isProcessWindowOpen() {
      return false;
    }

    @Override
    public void setProcessWindowOpen(final boolean open) { }

    @Override
    public BalloonHandler notifyProgressByBalloon(@NotNull MessageType type, @NotNull String htmlBody) {
      return () -> { };
    }

    @Override
    public BalloonHandler notifyProgressByBalloon(@NotNull MessageType type,
                                                  @NotNull String htmlBody,
                                                  @Nullable Icon icon,
                                                  @Nullable HyperlinkListener listener) {
      return () -> { };
    }
  }

  @Override
  public void releaseFrame(@NotNull ProjectFrameHelper frameHelper) {
    frameHelper.getFrame().dispose();
  }
}