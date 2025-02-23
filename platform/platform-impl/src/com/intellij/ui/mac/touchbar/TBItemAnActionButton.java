// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ui.mac.touchbar;

import com.intellij.icons.AllIcons;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.ActionManagerEx;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.IconLoader;
import com.intellij.ui.mac.foundation.ID;
import com.intellij.util.ThreeState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;

import static java.awt.event.ComponentEvent.COMPONENT_FIRST;

class TBItemAnActionButton extends TBItemButton {
  private static final int ourRunConfigurationPopoverWidth = 143;

  public static final int SHOWMODE_IMAGE_ONLY = 0;
  public static final int SHOWMODE_TEXT_ONLY = 1;
  public static final int SHOWMODE_IMAGE_TEXT = 2;
  public static final int SHOWMODE_IMAGE_ONLY_IF_PRESENTED = 3;

  private @NotNull AnAction myAnAction;
  private @Nullable String myActionId;

  private int myShowMode = SHOWMODE_IMAGE_ONLY_IF_PRESENTED;
  private boolean myAutoVisibility = true;
  private boolean myHiddenWhenDisabled = false;

  private @Nullable Component myComponent;
  private @Nullable List<? extends TBItemAnActionButton> myLinkedButtons;

  TBItemAnActionButton(@NotNull String uid, @Nullable ItemListener listener, @NotNull AnAction action) {
    super(uid, listener);
    setAnAction(action);
    setModality(null);

    if (action instanceof Toggleable) {
      myFlags |= NSTLibrary.BUTTON_FLAG_TOGGLE;
    }
  }

  @Override
  public String toString() { return String.format("%s [%s]", myActionId, myUid); }

  TBItemAnActionButton setComponent(Component component/*for DataCtx*/) { myComponent = component; return this; }
  TBItemAnActionButton setModality(ModalityState modality) { setAction(this::_performAction, true, modality); return this; }
  TBItemAnActionButton setShowMode(int showMode) { myShowMode = showMode; return this; }

  void setLinkedButtons(@Nullable List<? extends TBItemAnActionButton> linkedButtons) { myLinkedButtons = linkedButtons; }

  @NotNull Presentation updateAnAction(boolean forceUseCached) {
    final Presentation presentation = myAnAction.getTemplatePresentation().clone();

    if (ApplicationManager.getApplication() == null) {
      if (myComponent instanceof JButton) {
        presentation.setEnabled(myComponent.isEnabled());
        presentation.setText(DialogWrapper.extractMnemonic(((JButton)myComponent).getText()).second);
      }
      return presentation;
    }

    final DataContext dctx = DataManager.getInstance().getDataContext(_getComponent());
    final ActionManager am = ActionManagerEx.getInstanceEx();
    final AnActionEvent e = new AnActionEvent(
      null,
      dctx,
      ActionPlaces.TOUCHBAR_GENERAL,
      presentation,
      am,
      0
    );

    try {
      ActionUtil.performFastUpdate(false, myAnAction, e, forceUseCached);
    } catch (IndexNotReadyException e1) {
      presentation.setEnabledAndVisible(false);
    }

    return presentation;
  }

  boolean isAutoVisibility() { return myAutoVisibility; }
  void setAutoVisibility(boolean autoVisibility) { myAutoVisibility = autoVisibility; }

  void setHiddenWhenDisabled(boolean hiddenWhenDisabled) { myHiddenWhenDisabled = hiddenWhenDisabled; }

  @NotNull AnAction getAnAction() { return myAnAction; }
  void setAnAction(@NotNull AnAction newAction) {
    // can be safely replaced without setAction (because _performAction will use updated reference to AnAction)
    myAnAction = newAction;
    myActionId = ApplicationManager.getApplication() == null ? newAction.toString() : ActionManager.getInstance().getId(newAction);
  }

  // returns true when visibility changed
  boolean updateVisibility(Presentation presentation) { // called from EDT
    if (!myAutoVisibility)
      return false;

    final boolean isVisible = presentation.isVisible() && (presentation.isEnabled() || !myHiddenWhenDisabled);
    boolean visibilityChanged = isVisible != myIsVisible;
    if (visibilityChanged) {
      myIsVisible = isVisible;
      // System.out.println(String.format("%s: visibility changed, now is [%s]", toString(), isVisible ? "visible" : "hidden"));
    }
    if ("RunConfiguration".equals(myActionId))
      visibilityChanged = visibilityChanged || _setLinkedVisibility(presentation.getIcon() != AllIcons.General.Add);

    return visibilityChanged;
  }
  void updateView(Presentation presentation) { // called from EDT
    if (!myIsVisible)
      return;

    Icon icon = null;
    if (myShowMode != SHOWMODE_TEXT_ONLY) {
      if (presentation.isEnabled())
        icon = presentation.getIcon();
      else {
        icon = presentation.getDisabledIcon();
        if (icon == null) {
          icon = presentation.getIcon() == null ? null : IconLoader.getDisabledIcon(presentation.getIcon());
        }
      }
      // if (icon == null) System.out.println("WARN: can't obtain icon, action " + myActionId + ", presentation = " + _printPresentation(presentation));
    }

    boolean isSelected = false;
    if (myAnAction instanceof Toggleable) {
      isSelected = Toggleable.isSelected(presentation) == ThreeState.YES;
      if (myNativePeer != ID.NIL && myActionId != null && myActionId.startsWith("Console.Jdbc.Execute")) // permanent update of toggleable-buttons of DataGrip
        myUpdateOptions |= NSTLibrary.BUTTON_UPDATE_FLAGS;
    }
    if ("RunConfiguration".equals(myActionId)) {
      if (presentation.getIcon() != AllIcons.General.Add) {
        setHasArrowIcon(true);
        setLayout(ourRunConfigurationPopoverWidth, 0, 5, 8);
      } else {
        setHasArrowIcon(false);
        setLayout(0, 0, 5, 8);
      }
    }

    final boolean hideText = myShowMode == SHOWMODE_IMAGE_ONLY || (myShowMode == SHOWMODE_IMAGE_ONLY_IF_PRESENTED && icon != null);
    final String text = hideText ? null : presentation.getText();

    update(icon, text, isSelected, !presentation.isEnabled());
  }

  private boolean _setLinkedVisibility(boolean visible) {
    if (myLinkedButtons == null)
      return false;
    boolean visibilityChanged = false;
    for (TBItemAnActionButton butt: myLinkedButtons) {
      if (butt.myAutoVisibility != visible)
        visibilityChanged = true;
      butt.setAutoVisibility(visible);
      butt.myIsVisible = visible;
    }
    return visibilityChanged;
  }

  private void _performAction() {
    if (ApplicationManager.getApplication() == null) {
      if (myComponent instanceof JButton)
        ((JButton)myComponent).doClick();
      return;
    }

    final ActionManagerEx actionManagerEx = ActionManagerEx.getInstanceEx();
    final Component src = _getComponent();
    if (src == null) // KeyEvent can't have null source object
      return;

    final InputEvent ie = new KeyEvent(src, COMPONENT_FIRST, System.currentTimeMillis(), 0, 0, '\0');
    actionManagerEx.tryToExecute(myAnAction, ie, src, ActionPlaces.TOUCHBAR_GENERAL, true);

    if (myAnAction instanceof Toggleable) // to update 'selected'-state after action has been performed
      myUpdateOptions |= NSTLibrary.BUTTON_UPDATE_FLAGS;
  }

  private Component _getComponent() { return myComponent != null ? myComponent : _getCurrentFocusComponent(); }

  private static Component _getCurrentFocusComponent() {
    final KeyboardFocusManager focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
    Component focusOwner = focusManager.getFocusOwner();
    if (focusOwner == null)
      focusOwner = focusManager.getPermanentFocusOwner();
    if (focusOwner == null) {
      // LOG.info(String.format("WARNING: [%s:%s] _getCurrentFocusContext: null focus-owner, use focused window", myUid, myActionId));
      return focusManager.getFocusedWindow();
    }
    return focusOwner;
  }

  private static String _printPresentation(Presentation presentation) {
    StringBuilder sb = new StringBuilder();

    if (presentation.getText() != null && !presentation.getText().isEmpty())
      sb.append(String.format("text='%s'", presentation.getText()));

    {
      final Icon icon = presentation.getIcon();
      if (icon != null) {
        if (sb.length() != 0)
          sb.append(", ");
        sb.append(String.format("icon: %dx%d", icon.getIconWidth(), icon.getIconHeight()));
      }
    }

    {
      final Icon disabledIcon = presentation.getDisabledIcon();
      if (disabledIcon != null) {
        if (sb.length() != 0)
          sb.append(", ");
        sb.append(String.format("dis-icon: %dx%d", disabledIcon.getIconWidth(), disabledIcon.getIconHeight()));
      }
    }

    if (sb.length() != 0)
      sb.append(", ");
    sb.append(presentation.isVisible() ? "visible" : "hidden");

    sb.append(presentation.isEnabled() ? ", enabled" : ", disabled");

    return sb.toString();
  }
}
