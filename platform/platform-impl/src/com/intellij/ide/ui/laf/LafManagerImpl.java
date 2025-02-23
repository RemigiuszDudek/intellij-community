// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.ui.laf;

import com.intellij.CommonBundle;
import com.intellij.icons.AllIcons;
import com.intellij.ide.IdeBundle;
import com.intellij.ide.WelcomeWizardUtil;
import com.intellij.ide.ui.*;
import com.intellij.ide.ui.laf.darcula.DarculaInstaller;
import com.intellij.ide.ui.laf.darcula.DarculaLaf;
import com.intellij.ide.ui.laf.darcula.DarculaLookAndFeelInfo;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.RoamingType;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.impl.EditorColorsManagerImpl;
import com.intellij.openapi.extensions.ExtensionPointListener;
import com.intellij.openapi.extensions.PluginDescriptor;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.util.PopupUtil;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.UserDataHolder;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.AppUIUtil;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.ScreenUtil;
import com.intellij.ui.components.BasicOptionButtonUI;
import com.intellij.ui.mac.MacPopupMenuUI;
import com.intellij.ui.popup.OurHeavyWeightPopup;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.util.EventDispatcher;
import com.intellij.util.IJSwingUtilities;
import com.intellij.util.IconUtil;
import com.intellij.util.ObjectUtils;
import com.intellij.util.ui.*;
import org.intellij.lang.annotations.JdkConstants;
import org.jdom.Element;
import org.jetbrains.annotations.*;

import javax.swing.*;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.UIResource;
import javax.swing.plaf.metal.DefaultMetalTheme;
import javax.swing.plaf.metal.MetalLookAndFeel;
import javax.swing.text.DefaultEditorKit;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.*;

@State(name = "LafManager", storages = @Storage(value = "laf.xml", roamingType = RoamingType.PER_OS))
public final class LafManagerImpl extends LafManager implements PersistentStateComponent<Element>, Disposable {
  private static final Logger LOG = Logger.getInstance("#com.intellij.ide.ui.LafManager");

  @NonNls private static final String ELEMENT_LAF = "laf";
  @NonNls private static final String ATTRIBUTE_CLASS_NAME = "class-name";
  @NonNls private static final String ATTRIBUTE_THEME_NAME = "themeId";

  private static final String DARCULA_EDITOR_THEME_KEY = "Darcula.SavedEditorTheme";
  private static final String DEFAULT_EDITOR_THEME_KEY = "Default.SavedEditorTheme";

  @NonNls private static final String[] ourPatchableFontResources = {"Button.font", "ToggleButton.font", "RadioButton.font",
    "CheckBox.font", "ColorChooser.font", "ComboBox.font", "Label.font", "List.font", "MenuBar.font", "MenuItem.font",
    "MenuItem.acceleratorFont", "RadioButtonMenuItem.font", "CheckBoxMenuItem.font", "Menu.font", "PopupMenu.font", "OptionPane.font",
    "Panel.font", "ProgressBar.font", "ScrollPane.font", "Viewport.font", "TabbedPane.font", "Table.font", "TableHeader.font",
    "TextField.font", "FormattedTextField.font", "Spinner.font", "PasswordField.font", "TextArea.font", "TextPane.font", "EditorPane.font",
    "TitledBorder.font", "ToolBar.font", "ToolTip.font", "Tree.font"};

  @NonNls private static final String[] ourFileChooserTextKeys = {"FileChooser.viewMenuLabelText", "FileChooser.newFolderActionLabelText",
    "FileChooser.listViewActionLabelText", "FileChooser.detailsViewActionLabelText", "FileChooser.refreshActionLabelText"};

  private final EventDispatcher<LafManagerListener> myEventDispatcher = EventDispatcher.create(LafManagerListener.class);
  private UIManager.LookAndFeelInfo[] myLaFs;
  private final UIManager.LookAndFeelInfo myDefaultLightTheme;
  private final UIManager.LookAndFeelInfo myDefaultDarkTheme;
  private final UIDefaults ourDefaults;
  private UIManager.LookAndFeelInfo myCurrentLaf;
  private final Map<UIManager.LookAndFeelInfo, HashMap<String, Object>> myStoredDefaults = new HashMap<>();

  // A constant from Mac OS X implementation. See CPlatformWindow.WINDOW_ALPHA
  public static final String WINDOW_ALPHA = "Window.alpha";

  private static final Map<String, String> ourLafClassesAliases = new HashMap<>();

  static {
    ourLafClassesAliases.put("idea.dark.laf.classname", DarculaLookAndFeelInfo.CLASS_NAME);
  }

  private boolean myFirstSetup = true;

  /**
   * Invoked via reflection.
   */
  LafManagerImpl() {
    ourDefaults = (UIDefaults)UIManager.getDefaults().clone();

    List<UIManager.LookAndFeelInfo> lafList = new ArrayList<>();
    if (SystemInfo.isMac) {
      myDefaultLightTheme = new UIManager.LookAndFeelInfo("Light", IntelliJLaf.class.getName());
      lafList.add(myDefaultLightTheme);
    }
    else {
      myDefaultLightTheme = new IntelliJLookAndFeelInfo();
      lafList.add(myDefaultLightTheme);
      for (UIManager.LookAndFeelInfo laf : UIManager.getInstalledLookAndFeels()) {
        String name = laf.getName();
        if (!"Metal".equalsIgnoreCase(name)
            && !"CDE/Motif".equalsIgnoreCase(name)
            && !"Nimbus".equalsIgnoreCase(name)
            && !name.startsWith("Windows")
            && !"GTK+".equalsIgnoreCase(name)
            && !name.startsWith("JGoodies")) {
          lafList.add(laf);
        }
      }
    }

    lafList.add(myDefaultDarkTheme = new DarculaLookAndFeelInfo());


    for (UIThemeProvider provider : UIThemeProvider.EP_NAME.getExtensionList()) {
      UITheme x = provider.createTheme();
      if (x != null) {
        lafList.add(new UIThemeBasedLookAndFeelInfo(x));
      }
    }
    myLaFs = lafList.toArray(new UIManager.LookAndFeelInfo[0]);

    sortThemesIfNecessary();

    myCurrentLaf = getDefaultLaf();
  }

  private void sortThemesIfNecessary() {
    if (!SystemInfo.isMac) {
      // do not sort LaFs on mac - the order is determined as Default, Darcula.
      // when we leave only system LaFs on other OSes, the order also should be determined as Default, Darcula

      Arrays.sort(myLaFs, (obj1, obj2) -> {
        String name1 = obj1.getName();
        String name2 = obj2.getName();
        return name1.compareToIgnoreCase(name2);
      });
    }
  }

  @Override
  public void addLafManagerListener(@NotNull LafManagerListener listener) {
    myEventDispatcher.addListener(listener);
  }

  @Override
  public void addLafManagerListener(@NotNull LafManagerListener listener, @NotNull Disposable disposable) {
    ApplicationManager.getApplication().getMessageBus().connect(disposable).subscribe(LafManagerListener.TOPIC, listener);
  }

  @Override
  public void removeLafManagerListener(@NotNull LafManagerListener listener) {
    myEventDispatcher.removeListener(listener);
  }

  @Override
  public void initializeComponent() {
    if (myCurrentLaf != null && !(myCurrentLaf instanceof UIThemeBasedLookAndFeelInfo)) {
      final UIManager.LookAndFeelInfo laf = findLaf(myCurrentLaf.getClassName());
      if (laf != null) {
        boolean needUninstall = StartupUiUtil.isUnderDarcula();
        setCurrentLookAndFeel(laf); // setup default LAF or one specified by readExternal.
        updateWizardLAF(needUninstall);
      }
    }

    if (myCurrentLaf instanceof UIThemeBasedLookAndFeelInfo && !((UIThemeBasedLookAndFeelInfo)myCurrentLaf).isInitialised()) {
      setCurrentLookAndFeel(myCurrentLaf);
    }

    updateUI();

    UIThemeProvider.EP_NAME.getPoint(null).addExtensionPointListener(new ExtensionPointListener<UIThemeProvider>() {
      @Override
      public void extensionAdded(@NotNull UIThemeProvider provider, @NotNull PluginDescriptor pluginDescriptor) {
        for (UIManager.LookAndFeelInfo feel : getInstalledLookAndFeels()) {
          if (feel instanceof UIThemeBasedLookAndFeelInfo && ((UIThemeBasedLookAndFeelInfo)feel).getTheme().getId().equals(provider.id)) {
            //provider is already registered
            return;
          }
        }
        UITheme theme = provider.createTheme();
        if (theme != null) {
          UIManager.LookAndFeelInfo[] newLafs = new UIManager.LookAndFeelInfo[myLaFs.length + 1];
          System.arraycopy(myLaFs, 0, newLafs, 0, myLaFs.length);
          String editorScheme = theme.getEditorScheme();
          if (editorScheme != null) {
            ((EditorColorsManagerImpl)EditorColorsManager.getInstance()).getSchemeManager()
              .loadBundledScheme(editorScheme, theme);
          }
          UIThemeBasedLookAndFeelInfo newTheme = new UIThemeBasedLookAndFeelInfo(theme);
          newLafs[newLafs.length - 1] = newTheme;
          myLaFs = newLafs;
          sortThemesIfNecessary();
          setCurrentLookAndFeel(newTheme);
          updateUI();
        }
      }

      @Override
      public void extensionRemoved(@NotNull UIThemeProvider provider, @NotNull PluginDescriptor pluginDescriptor) {
        UIManager.LookAndFeelInfo switchLafTo = null;
        ArrayList<UIManager.LookAndFeelInfo> lafs = new ArrayList<>();
        for (UIManager.LookAndFeelInfo lookAndFeel : getInstalledLookAndFeels()) {
          if (lookAndFeel instanceof UIThemeBasedLookAndFeelInfo && ((UIThemeBasedLookAndFeelInfo)lookAndFeel).getTheme().getId().equals(provider.id)) {
            if (lookAndFeel == getCurrentLookAndFeel()) {
              switchLafTo = ((UIThemeBasedLookAndFeelInfo)lookAndFeel).getTheme().isDark() ? myDefaultDarkTheme : myDefaultLightTheme;
            }
            continue;
          }
          lafs.add(lookAndFeel);
        }
        myLaFs = lafs.toArray(new UIManager.LookAndFeelInfo[0]);
        if (switchLafTo != null) {
          setCurrentLookAndFeel(switchLafTo);
          updateUI();
        }
      }
    }, false, this);
  }

  public void updateWizardLAF(boolean wasUnderDarcula) {
    if (WelcomeWizardUtil.getWizardLAF() != null) {
      if (StartupUiUtil.isUnderDarcula()) {
        DarculaInstaller.install();
      }
      else if (wasUnderDarcula) {
        DarculaInstaller.uninstall();
      }
      WelcomeWizardUtil.setWizardLAF(null);
    }
  }

  @Override
  public void dispose() {
  }

  @Override
  public void loadState(@NotNull final Element element) {
    String className = null;
    UIManager.LookAndFeelInfo laf = null;
    Element lafElement = element.getChild(ELEMENT_LAF);
    if (lafElement != null) {
      className = lafElement.getAttributeValue(ATTRIBUTE_CLASS_NAME);
      if (className != null && ourLafClassesAliases.containsKey(className)) {
        className = ourLafClassesAliases.get(className);
      }

      String themeId = lafElement.getAttributeValue(ATTRIBUTE_THEME_NAME);
      if (themeId != null) {
        laf = Arrays.stream(myLaFs).
          filter(l -> l instanceof UIThemeBasedLookAndFeelInfo && ((UIThemeBasedLookAndFeelInfo)l).getTheme().getId().equals(themeId)).
          findFirst().orElse(null);
      }
    }

    if (laf == null) {
      laf = findLaf(className);
    }
    // If LAF is undefined (wrong class name or something else) we have set default LAF anyway.
    if (laf == null) {
      laf = getDefaultLaf();
    }

    myCurrentLaf = laf;
  }

  @Override
  public Element getState() {
    Element element = new Element("state");
    UIManager.LookAndFeelInfo laf = myCurrentLaf;
    if (laf instanceof TempUIThemeBasedLookAndFeelInfo) {
      laf = ((TempUIThemeBasedLookAndFeelInfo)laf).getPreviousLaf();
    }
    if (laf != null) {
      String className = laf.getClassName();
      if (className != null) {
        Element child = new Element(ELEMENT_LAF);
        child.setAttribute(ATTRIBUTE_CLASS_NAME, className);

        if (laf instanceof UIThemeBasedLookAndFeelInfo) {
          child.setAttribute(ATTRIBUTE_THEME_NAME, ((UIThemeBasedLookAndFeelInfo)laf).getTheme().getId());
        }
        element.addContent(child);
      }
    }
    return element;
  }

  @NotNull
  @Override
  public UIManager.LookAndFeelInfo[] getInstalledLookAndFeels() {
    return myLaFs.clone();
  }

  @Override
  public UIManager.LookAndFeelInfo getCurrentLookAndFeel() {
    return myCurrentLaf;
  }

  public UIManager.LookAndFeelInfo getDefaultLaf() {
    String wizardLafName = WelcomeWizardUtil.getWizardLAF();
    if (wizardLafName != null) {
      UIManager.LookAndFeelInfo laf = findLaf(wizardLafName);
      if (laf != null) return laf;
      LOG.error("Could not find wizard L&F: " + wizardLafName);
    }

    if (SystemInfo.isMac) {
      String className = IntelliJLaf.class.getName();
      UIManager.LookAndFeelInfo laf = findLaf(className);
      if (laf != null) return laf;
      LOG.error("Could not find OS X L&F: " + className);
    }

    String appLafName = WelcomeWizardUtil.getDefaultLAF();
    if (appLafName != null) {
      UIManager.LookAndFeelInfo laf = findLaf(appLafName);
      if (laf != null) return laf;
      LOG.error("Could not find app L&F: " + appLafName);
    }

    String defaultLafName = IntelliJLaf.class.getName();
    UIManager.LookAndFeelInfo laf = findLaf(defaultLafName);
    if (laf != null) return laf;
    throw new IllegalStateException("No default L&F found: " + defaultLafName);
  }

  @Nullable
  private UIManager.LookAndFeelInfo findLaf(@Nullable String className) {
    return Arrays.stream(myLaFs).
      filter(l -> !(l instanceof UIThemeBasedLookAndFeelInfo) && Comparing.equal(l.getClassName(), className)).
      findFirst().
      orElse(null);
  }

  /**
   * Sets current LAF. The method doesn't update component hierarchy.
   */
  @Override
  public void setCurrentLookAndFeel(@NotNull UIManager.LookAndFeelInfo lookAndFeelInfo) {
    UIManager.LookAndFeelInfo oldLaf = myCurrentLaf;

    if (myCurrentLaf instanceof UIThemeBasedLookAndFeelInfo) {
      ((UIThemeBasedLookAndFeelInfo)myCurrentLaf).dispose();
    }

    if (findLaf(lookAndFeelInfo.getClassName()) == null) {
      LOG.error("unknown LookAndFeel : " + lookAndFeelInfo);
      return;
    }

    UIManager.getDefaults().clear();
    UIManager.getDefaults().putAll(ourDefaults);

    // Set L&F
    if (IdeaLookAndFeelInfo.CLASS_NAME.equals(lookAndFeelInfo.getClassName())) { // that is IDEA default LAF
      IdeaLaf laf = new IdeaLaf();
      MetalLookAndFeel.setCurrentTheme(new IdeaBlueMetalTheme());
      try {
        UIManager.setLookAndFeel(laf);
      }
      catch (Exception e) {
        Messages.showMessageDialog(
          IdeBundle.message("error.cannot.set.look.and.feel", lookAndFeelInfo.getName(), e.getMessage()),
          CommonBundle.getErrorTitle(),
          Messages.getErrorIcon()
        );
        return;
      }
    }
    else if (DarculaLookAndFeelInfo.CLASS_NAME.equals(lookAndFeelInfo.getClassName())) {
      DarculaLaf laf = new DarculaLaf();
      try {
        UIManager.setLookAndFeel(laf);
        AppUIUtil.updateForDarcula(true);
      }
      catch (Exception e) {
        Messages.showMessageDialog(
          IdeBundle.message("error.cannot.set.look.and.feel", lookAndFeelInfo.getName(), e.getMessage()),
          CommonBundle.getErrorTitle(),
          Messages.getErrorIcon()
        );
        return;
      }
    }
    else { // non default LAF
      try {
        LookAndFeel laf = ((LookAndFeel)Class.forName(lookAndFeelInfo.getClassName()).newInstance());
        if (laf instanceof MetalLookAndFeel) {
          MetalLookAndFeel.setCurrentTheme(new DefaultMetalTheme());
        }
        if (laf instanceof UserDataHolder && lookAndFeelInfo instanceof UIThemeBasedLookAndFeelInfo) {
          ((UserDataHolder)laf).putUserData(UIUtil.LAF_WITH_THEME_KEY, Boolean.TRUE);
        }
        UIManager.setLookAndFeel(laf);
      }
      catch (Exception e) {
        Messages.showMessageDialog(
          IdeBundle.message("error.cannot.set.look.and.feel", lookAndFeelInfo.getName(), e.getMessage()),
          CommonBundle.getErrorTitle(),
          Messages.getErrorIcon()
        );
        return;
      }
    }

    if (lookAndFeelInfo instanceof UIThemeBasedLookAndFeelInfo) {
      try {
        ((UIThemeBasedLookAndFeelInfo)lookAndFeelInfo).installTheme(UIManager.getLookAndFeelDefaults());
      }
      catch (Exception e) {
        Messages.showMessageDialog(
          IdeBundle.message("error.cannot.set.look.and.feel", lookAndFeelInfo.getName(), e.getMessage()),
          CommonBundle.getErrorTitle(),
          Messages.getErrorIcon()
        );
        return;
      }
    }

    if (SystemInfo.isMacOSYosemite) {
      installMacOSXFonts(UIManager.getLookAndFeelDefaults());
    }

    myCurrentLaf = ObjectUtils.chooseNotNull(lookAndFeelInfo, findLaf(lookAndFeelInfo.getClassName()));

    if (!myFirstSetup) {
      ApplicationManager.getApplication().invokeLater(() -> updateEditorSchemeIfNecessary(oldLaf));
    }
    myFirstSetup = false;
  }

  private void updateEditorSchemeIfNecessary(UIManager.LookAndFeelInfo oldLaf) {
    if (oldLaf instanceof TempUIThemeBasedLookAndFeelInfo || myCurrentLaf instanceof TempUIThemeBasedLookAndFeelInfo) return;
    if (myCurrentLaf instanceof UIThemeBasedLookAndFeelInfo) {
      if (((UIThemeBasedLookAndFeelInfo)myCurrentLaf).getTheme().getEditorSchemeName() != null) {
        return;
      }
    }

    boolean dark = StartupUiUtil.isUnderDarcula();
    EditorColorsManager colorsManager = EditorColorsManager.getInstance();
    EditorColorsScheme current = colorsManager.getGlobalScheme();
    boolean wasUITheme = oldLaf instanceof UIThemeBasedLookAndFeelInfo;
    if (dark != ColorUtil.isDark(current.getDefaultBackground()) || wasUITheme) {
      String targetScheme = dark ? DarculaLaf.NAME : EditorColorsScheme.DEFAULT_SCHEME_NAME;
      PropertiesComponent properties = PropertiesComponent.getInstance();
      String savedEditorThemeKey = dark ? DARCULA_EDITOR_THEME_KEY : DEFAULT_EDITOR_THEME_KEY;
      String toSavedEditorThemeKey = dark ? DEFAULT_EDITOR_THEME_KEY : DARCULA_EDITOR_THEME_KEY;
      String themeName =  properties.getValue(savedEditorThemeKey);
      if (themeName != null && colorsManager.getScheme(themeName) != null) {
        targetScheme = themeName;
      }
      if (!wasUITheme) {
        properties.setValue(toSavedEditorThemeKey, current.getName(), dark ? EditorColorsScheme.DEFAULT_SCHEME_NAME : DarculaLaf.NAME);
      }

      EditorColorsScheme scheme = colorsManager.getScheme(targetScheme);
      if (scheme != null) {
        colorsManager.setGlobalScheme(scheme);
      }
    }
    UISettings.getShadowInstance().fireUISettingsChanged();
    ActionToolbarImpl.updateAllToolbarsImmediately();
  }

  /**
   * @deprecated Use {@link AppUIUtil#updateForDarcula(boolean)}
   */
  @Deprecated
  @ApiStatus.ScheduledForRemoval
  public static void updateForDarcula(boolean isDarcula) {
    AppUIUtil.updateForDarcula(isDarcula);
  }

  @Nullable
  private static Icon getAquaMenuDisabledIcon() {
    final Icon arrowIcon = (Icon)UIManager.get("Menu.arrowIcon");
    if (arrowIcon != null) {
      return IconLoader.getDisabledIcon(arrowIcon);
    }

    return null;
  }

  /**
   * Updates LAF of all windows. The method also updates font of components
   * as it's configured in {@code UISettings}.
   */
  @Override
  public void updateUI() {
    final UIDefaults uiDefaults = UIManager.getLookAndFeelDefaults();

    fixPopupWeight();

    fixMenuIssues(uiDefaults);

    initInputMapDefaults(uiDefaults);

    uiDefaults.put("Button.defaultButtonFollowsFocus", Boolean.FALSE);
    uiDefaults.put("Balloon.error.textInsets", new JBInsets(3, 8, 3, 8).asUIResource());

    patchFileChooserStrings(uiDefaults);

    patchLafFonts(uiDefaults);

    patchListUI(uiDefaults);
    patchTreeUI(uiDefaults);

    patchHiDPI(uiDefaults);

    fixProgressBar(uiDefaults);

    fixOptionButton(uiDefaults);

    for (Frame frame : Frame.getFrames()) {
      updateUI(frame);
    }

    ApplicationManager.getApplication().getMessageBus().syncPublisher(LafManagerListener.TOPIC).lookAndFeelChanged(this);
    myEventDispatcher.getMulticaster().lookAndFeelChanged(this);
  }

  @NotNull
  private static FontUIResource getFont(String yosemite, int size, @JdkConstants.FontStyle int style) {
    if (SystemInfo.isMacOSElCapitan) {
      // Text family should be used for relatively small sizes (<20pt), don't change to Display
      // see more about SF https://medium.com/@mach/the-secret-of-san-francisco-fonts-4b5295d9a745#.2ndr50z2v
      Font font = new Font(".SF NS Text", style, size);
      if (!StartupUiUtil.isDialogFont(font)) {
        return new FontUIResource(font);
      }
    }
    return new FontUIResource(yosemite, style, size);
  }


  public static void installMacOSXFonts(UIDefaults defaults) {
    final String face = "HelveticaNeue-Regular";
    final FontUIResource uiFont = getFont(face, 13, Font.PLAIN);
    initFontDefaults(defaults, uiFont);
    for (Object key : new HashSet<>(defaults.keySet())) {
      if (!(key instanceof String)) continue;
      if (!StringUtil.endsWithIgnoreCase(((String)key), "font")) continue;
      Object value = defaults.get(key);
      if (value instanceof FontUIResource) {
        FontUIResource font = (FontUIResource)value;
        if (font.getFamily().equals("Lucida Grande") || font.getFamily().equals("Serif")) {
          if (!key.toString().contains("Menu")) {
            defaults.put(key, getFont(face, font.getSize(), font.getStyle()));
          }
        }
      }
    }

    FontUIResource uiFont11 = getFont(face, 11, Font.PLAIN);
    defaults.put("TableHeader.font", uiFont11);

    FontUIResource buttonFont = getFont("HelveticaNeue-Medium", 13, Font.PLAIN);
    defaults.put("Button.font", buttonFont);
    Font menuFont = getFont("Lucida Grande", 14, Font.PLAIN);
    defaults.put("Menu.font", menuFont);
    defaults.put("MenuItem.font", menuFont);
    defaults.put("MenuItem.acceleratorFont", menuFont);
    defaults.put("PasswordField.font", defaults.getFont("TextField.font"));
  }

  private static void patchBorder(UIDefaults defaults, String key) {
    if (defaults.getBorder(key) == null) {
      defaults.put(key, JBUI.Borders.empty(1, 0).asUIResource());
    }
  }

  private static void patchListUI(UIDefaults defaults) {
    patchBorder(defaults, "List.border");
  }

  private static void patchTreeUI(UIDefaults defaults) {
    patchBorder(defaults, "Tree.border");
    if (Registry.is("ide.tree.ui.experimental")) {
      defaults.put("TreeUI", "com.intellij.ui.tree.ui.DefaultTreeUI");
      defaults.put("Tree.repaintWholeRow", true);
    }
    if (isUnsupported(defaults.getIcon("Tree.collapsedIcon"))) {
      defaults.put("Tree.collapsedIcon", LafIconLookup.getIcon("treeCollapsed"));
      defaults.put("Tree.collapsedSelectedIcon", LafIconLookup.getSelectedIcon("treeCollapsed"));
    }
    if (isUnsupported(defaults.getIcon("Tree.expandedIcon"))) {
      defaults.put("Tree.expandedIcon", LafIconLookup.getIcon("treeExpanded"));
      defaults.put("Tree.expandedSelectedIcon", LafIconLookup.getSelectedIcon("treeExpanded"));
    }
  }

  /**
   * @param icon an icon retrieved from L&F
   * @return {@code true} if an icon is not specified or if it is declared in some Swing L&F
   * (such icons do not have a variant to paint in selected row)
   */
  private static boolean isUnsupported(@Nullable Icon icon) {
    String name = icon == null ? null : icon.getClass().getName();
    return name == null || name.startsWith("javax.swing.plaf.") || name.startsWith("com.sun.java.swing.plaf.");
  }

  private static void patchHiDPI(UIDefaults defaults) {
    Object prevScaleVal = defaults.get("hidpi.scaleFactor");
    // used to normalize previously patched values
    float prevScale = prevScaleVal != null ? (Float)prevScaleVal : 1f;

    if (prevScale == JBUIScale.scale(1f) && prevScaleVal != null) return;

    List<String> myIntKeys = Arrays.asList("Tree.leftChildIndent",
                                           "Tree.rightChildIndent",
                                           "Tree.rowHeight",
                                           "SettingsTree.rowHeight",
                                           "Table.rowHeight",
                                           "List.rowHeight");

    List<String> myDimensionKeys = Arrays.asList("Slider.horizontalSize",
                                                 "Slider.verticalSize",
                                                 "Slider.minimumHorizontalSize",
                                                 "Slider.minimumVerticalSize");

    for (Map.Entry<Object, Object> entry : defaults.entrySet()) {
      Object value = entry.getValue();
      String key = entry.getKey().toString();
      if (value instanceof Dimension) {
        if (value instanceof UIResource || myDimensionKeys.contains(key)) {
          entry.setValue(JBUI.size((Dimension)value).asUIResource());
        }
      }
      else if (value instanceof Insets) {
        if (value instanceof UIResource) {
          entry.setValue(JBUI.insets(((Insets)value)).asUIResource());
        }
      }
      else if (value instanceof Integer) {
        if (key.endsWith(".maxGutterIconWidth") || myIntKeys.contains(key)) {
          int normValue = (int)((Integer)value / prevScale);
          entry.setValue(Integer.valueOf(JBUIScale.scale(normValue)));
        }
      }
    }
    defaults.put("hidpi.scaleFactor", JBUIScale.scale(1f));
  }

  private static void fixMenuIssues(@NotNull UIDefaults uiDefaults) {
    if (SystemInfo.isMac && UIUtil.isUnderIntelliJLaF()) {
      // update ui for popup menu to get round corners
      uiDefaults.put("PopupMenuUI", MacPopupMenuUI.class.getCanonicalName());
      uiDefaults.put("Menu.invertedArrowIcon", AllIcons.Mac.Tree_white_right_arrow);
      uiDefaults.put("Menu.disabledArrowIcon", getAquaMenuDisabledIcon());
    }

    if (UIUtil.isUnderWin10LookAndFeel()) {
      uiDefaults.put("Menu.arrowIcon", new Win10MenuArrowIcon());
    }
    else if ((SystemInfo.isLinux || SystemInfo.isWindows) && (UIUtil.isUnderIntelliJLaF() || StartupUiUtil.isUnderDarcula())) {
      uiDefaults.put("Menu.arrowIcon", new DefaultMenuArrowIcon(AllIcons.General.ArrowRight));
    }

    uiDefaults.put("MenuItem.background", UIManager.getColor("Menu.background"));
  }

  private static void fixProgressBar(UIDefaults uiDefaults) {
    if (!UIUtil.isUnderIntelliJLaF() && !StartupUiUtil.isUnderDarcula()) {
      uiDefaults.put("ProgressBarUI", "com.intellij.ide.ui.laf.darcula.ui.DarculaProgressBarUI");
      uiDefaults.put("ProgressBar.border", "com.intellij.ide.ui.laf.darcula.ui.DarculaProgressBarBorder");
    }
  }

  /**
   * NOTE: This code could be removed if {@link com.intellij.ui.components.JBOptionButton} is moved to [platform-impl]
   * and default UI is created there directly.
   */
  static void fixOptionButton(UIDefaults uiDefaults) {
    if (!UIUtil.isUnderIntelliJLaF() && !StartupUiUtil.isUnderDarcula()) {
      uiDefaults.put("OptionButtonUI", BasicOptionButtonUI.class.getCanonicalName());
    }
  }

  /**
   * The following code is a trick! By default Swing uses lightweight and "medium" weight
   * popups to show JPopupMenu. The code below force the creation of real heavyweight menus -
   * this increases speed of popups and allows to get rid of some drawing artifacts.
   */
  private static void fixPopupWeight() {
    int popupWeight = OurPopupFactory.WEIGHT_MEDIUM;
    String property = System.getProperty("idea.popup.weight");
    if (property != null) property = StringUtil.toLowerCase(property).trim();
    if (SystemInfo.isMacOSLeopard) {
      // force heavy weight popups under Leopard, otherwise they don't have shadow or any kind of border.
      popupWeight = OurPopupFactory.WEIGHT_HEAVY;
    }
    else if (property == null) {
      // use defaults if popup weight isn't specified
      if (SystemInfo.isWindows) {
        popupWeight = OurPopupFactory.WEIGHT_HEAVY;
      }
    }
    else {
      if ("light".equals(property)) {
        popupWeight = OurPopupFactory.WEIGHT_LIGHT;
      }
      else if ("heavy".equals(property)) {
        popupWeight = OurPopupFactory.WEIGHT_HEAVY;
      }
      else if (!"medium".equals(property)) {
        LOG.error("Illegal value of property \"idea.popup.weight\": " + property);
      }
    }

    PopupFactory factory = PopupFactory.getSharedInstance();
    if (!(factory instanceof OurPopupFactory)) {
      factory = new OurPopupFactory(factory);
      PopupFactory.setSharedInstance(factory);
    }
    PopupUtil.setPopupType(factory, popupWeight);
  }

  private static void patchFileChooserStrings(final UIDefaults defaults) {
    if (!defaults.containsKey(ourFileChooserTextKeys[0])) {
      // Alloy L&F does not define strings for names of context menu actions, so we have to patch them in here
      for (String key : ourFileChooserTextKeys) {
        defaults.put(key, IdeBundle.message(key));
      }
    }
  }

  private void patchLafFonts(UIDefaults uiDefaults) {
    //if (JBUI.isHiDPI()) {
    //  HashMap<Object, Font> newFonts = new HashMap<Object, Font>();
    //  for (Object key : uiDefaults.keySet().toArray()) {
    //    Object val = uiDefaults.get(key);
    //    if (val instanceof Font) {
    //      newFonts.put(key, JBFont.create((Font)val));
    //    }
    //  }
    //  for (Map.Entry<Object, Font> entry : newFonts.entrySet()) {
    //    uiDefaults.put(entry.getKey(), entry.getValue());
    //  }
    //} else
    UISettings uiSettings = UISettings.getInstance();
    if (uiSettings.getOverrideLafFonts()) {
      storeOriginalFontDefaults(uiDefaults);
      initFontDefaults(uiDefaults, UIUtil.getFontWithFallback(uiSettings.getFontFace(), Font.PLAIN, uiSettings.getFontSize()));
      JBUIScale.setUserScaleFactor(JBUIScale.getFontScale(uiSettings.getFontSize()));
    }
    else {
      restoreOriginalFontDefaults(uiDefaults);
    }
  }

  private void restoreOriginalFontDefaults(UIDefaults defaults) {
    UIManager.LookAndFeelInfo lf = getCurrentLookAndFeel();
    HashMap<String, Object> lfDefaults = myStoredDefaults.get(lf);
    if (lfDefaults != null) {
      for (String resource : ourPatchableFontResources) {
        defaults.put(resource, lfDefaults.get(resource));
      }
    }
    JBUIScale.setUserScaleFactor(JBUIScale.getFontScale(JBFont.label().getSize()));
  }

  private void storeOriginalFontDefaults(UIDefaults defaults) {
    UIManager.LookAndFeelInfo lf = getCurrentLookAndFeel();
    HashMap<String, Object> lfDefaults = myStoredDefaults.get(lf);
    if (lfDefaults == null) {
      lfDefaults = new HashMap<>();
      for (String resource : ourPatchableFontResources) {
        lfDefaults.put(resource, defaults.get(resource));
      }
      myStoredDefaults.put(lf, lfDefaults);
    }
  }

  private static void updateUI(Window window) {
    IJSwingUtilities.updateComponentTreeUI(window);
    Window[] children = window.getOwnedWindows();
    for (Window w : children) {
      IJSwingUtilities.updateComponentTreeUI(w);
    }
  }

  /**
   * Repaints all displayable window.
   */
  @Override
  public void repaintUI() {
    Frame[] frames = Frame.getFrames();
    for (Frame frame : frames) {
      repaintUI(frame);
    }
  }

  private static void repaintUI(Window window) {
    if (!window.isDisplayable()) {
      return;
    }
    window.repaint();
    Window[] children = window.getOwnedWindows();
    for (Window aChildren : children) {
      repaintUI(aChildren);
    }
  }

  private static void installCutCopyPasteShortcuts(InputMap inputMap, boolean useSimpleActionKeys) {
    String copyActionKey = useSimpleActionKeys ? "copy" : DefaultEditorKit.copyAction;
    String pasteActionKey = useSimpleActionKeys ? "paste" : DefaultEditorKit.pasteAction;
    String cutActionKey = useSimpleActionKeys ? "cut" : DefaultEditorKit.cutAction;
    // Ctrl+Ins, Shift+Ins, Shift+Del
    inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.CTRL_MASK | InputEvent.CTRL_DOWN_MASK), copyActionKey);
    inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.SHIFT_MASK | InputEvent.SHIFT_DOWN_MASK), pasteActionKey);
    inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, InputEvent.SHIFT_MASK | InputEvent.SHIFT_DOWN_MASK), cutActionKey);
    // Ctrl+C, Ctrl+V, Ctrl+X
    inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_MASK | InputEvent.CTRL_DOWN_MASK), copyActionKey);
    inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_MASK | InputEvent.CTRL_DOWN_MASK), pasteActionKey);
    inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_X, InputEvent.CTRL_MASK | InputEvent.CTRL_DOWN_MASK), DefaultEditorKit.cutAction);
  }

  @SuppressWarnings({"HardCodedStringLiteral"})
  public static void initInputMapDefaults(UIDefaults defaults) {
    // Make ENTER work in JTrees
    InputMap treeInputMap = (InputMap)defaults.get("Tree.focusInputMap");
    if (treeInputMap != null) { // it's really possible. For example,  GTK+ doesn't have such map
      treeInputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "toggle");
    }
    // Cut/Copy/Paste in JTextAreas
    InputMap textAreaInputMap = (InputMap)defaults.get("TextArea.focusInputMap");
    if (textAreaInputMap != null) { // It really can be null, for example when LAF isn't properly initialized (Alloy license problem)
      installCutCopyPasteShortcuts(textAreaInputMap, false);
    }
    // Cut/Copy/Paste in JTextFields
    InputMap textFieldInputMap = (InputMap)defaults.get("TextField.focusInputMap");
    if (textFieldInputMap != null) { // It really can be null, for example when LAF isn't properly initialized (Alloy license problem)
      installCutCopyPasteShortcuts(textFieldInputMap, false);
    }
    // Cut/Copy/Paste in JPasswordField
    InputMap passwordFieldInputMap = (InputMap)defaults.get("PasswordField.focusInputMap");
    if (passwordFieldInputMap != null) { // It really can be null, for example when LAF isn't properly initialized (Alloy license problem)
      installCutCopyPasteShortcuts(passwordFieldInputMap, false);
    }
    // Cut/Copy/Paste in JTables
    InputMap tableInputMap = (InputMap)defaults.get("Table.ancestorInputMap");
    if (tableInputMap != null) { // It really can be null, for example when LAF isn't properly initialized (Alloy license problem)
      installCutCopyPasteShortcuts(tableInputMap, true);
    }
  }

  @SuppressWarnings({"HardCodedStringLiteral"})
  public static void initFontDefaults(@NotNull UIDefaults defaults, @NotNull FontUIResource uiFont) {
    defaults.put("Tree.ancestorInputMap", null);
    FontUIResource textFont = new FontUIResource(uiFont);
    FontUIResource monoFont = new FontUIResource("Monospaced", Font.PLAIN, uiFont.getSize());

    for (String fontResource : ourPatchableFontResources) {
      defaults.put(fontResource, uiFont);
    }

    if (!SystemInfo.isMac) {
      defaults.put("PasswordField.font", monoFont);
    }
    defaults.put("TextArea.font", monoFont);
    defaults.put("TextPane.font", textFont);
    defaults.put("EditorPane.font", textFont);
  }


  private static class OurPopupFactory extends PopupFactory {
    public static final int WEIGHT_LIGHT = 0;
    public static final int WEIGHT_MEDIUM = 1;
    public static final int WEIGHT_HEAVY = 2;

    private final PopupFactory myDelegate;

    OurPopupFactory(final PopupFactory delegate) {
      myDelegate = delegate;
    }

    @Override
    public Popup getPopup(final Component owner, final Component contents, final int x, final int y) {
      final Point point = fixPopupLocation(contents, x, y);

      final int popupType = PopupUtil.getPopupType(this);
      if (popupType == WEIGHT_HEAVY && OurHeavyWeightPopup.isEnabled()) {
        return new OurHeavyWeightPopup(owner, contents, point.x, point.y);
      }
      if (popupType >= 0) {
        PopupUtil.setPopupType(myDelegate, popupType);
      }

      Popup popup = myDelegate.getPopup(owner, contents, point.x, point.y);
      Window window = UIUtil.getWindow(contents);
      String cleanupKey = "LafManagerImpl.rootPaneCleanup";
      boolean isHeavyWeightPopup = window instanceof RootPaneContainer && window != UIUtil.getWindow(owner);
      if (isHeavyWeightPopup) {
        UIUtil.markAsTypeAheadAware(window);
        window.setMinimumSize(null); // clear min-size from prev invocations on JBR11
      }
      if (isHeavyWeightPopup && ((RootPaneContainer)window).getRootPane().getClientProperty(cleanupKey) == null) {
        final JRootPane rootPane = ((RootPaneContainer)window).getRootPane();
        rootPane.putClientProperty(WINDOW_ALPHA, 1.0f);
        rootPane.putClientProperty(cleanupKey, cleanupKey);
        window.addWindowListener(new WindowAdapter() {
          @Override
          public void windowOpened(WindowEvent e) {
            // cleanup will be handled by AbstractPopup wrapper
            if (PopupUtil.getPopupContainerFor(rootPane) != null) {
              window.removeWindowListener(this);
              rootPane.putClientProperty(cleanupKey, null);
            }
          }

          @Override
          public void windowClosed(WindowEvent e) {
            window.removeWindowListener(this);
            rootPane.putClientProperty(cleanupKey, null);
            DialogWrapper.cleanupRootPane(rootPane);
            DialogWrapper.cleanupWindowListeners(window);
          }
        });
      }
      return popup;
    }

    private static Point fixPopupLocation(final Component contents, final int x, final int y) {
      if (!(contents instanceof JToolTip)) return new Point(x, y);

      final PointerInfo info;
      try {
        info = MouseInfo.getPointerInfo();
      }
      catch (InternalError e) {
        // http://www.jetbrains.net/jira/browse/IDEADEV-21390
        // may happen under Mac OSX 10.5
        return new Point(x, y);
      }
      int deltaY = 0;

      if (info != null) {
        final Point mouse = info.getLocation();
        deltaY = mouse.y - y;
      }

      final Dimension size = contents.getPreferredSize();
      final Rectangle rec = new Rectangle(new Point(x, y), size);
      ScreenUtil.moveRectangleToFitTheScreen(rec);

      if (rec.y < y) {
        rec.y += deltaY;
      }

      return rec.getLocation();
    }
  }

  private abstract static class MenuArrowIcon implements Icon, UIResource {
    private final Icon icon;
    private final Icon selectedIcon;
    private final Icon disabledIcon;

    private MenuArrowIcon(Icon icon, Icon selectedIcon, Icon disabledIcon) {
      this.icon = icon;
      this.selectedIcon = selectedIcon;
      this.disabledIcon = disabledIcon;
    }

    @Override public void paintIcon(Component c, Graphics g, int x, int y) {
      JMenuItem b = (JMenuItem) c;
      ButtonModel model = b.getModel();

      if (!model.isEnabled()) {
        disabledIcon.paintIcon(c, g, x, y);
      } else if (model.isArmed() || ( c instanceof JMenu && model.isSelected())) {
        selectedIcon.paintIcon(c, g, x, y);
      } else {
        icon.paintIcon(c, g, x, y);
      }
    }

    @Override public int getIconWidth() {
      return icon.getIconWidth();
    }

    @Override public int getIconHeight() {
      return icon.getIconHeight();
    }
  }

  private static class DefaultMenuArrowIcon extends MenuArrowIcon {
    private static final boolean invert = StartupUiUtil.isUnderDarcula();

    private DefaultMenuArrowIcon(@NotNull Icon icon) {
      super(invert ? IconUtil.brighter(icon, 2) : IconUtil.darker(icon, 2),
            IconUtil.brighter(icon, 8),
            invert ? IconUtil.darker(icon, 2) : IconUtil.brighter(icon, 2));
    }
  }

  private static class Win10MenuArrowIcon extends MenuArrowIcon {
    private static final String NAME = "menuTriangle";
    private Win10MenuArrowIcon() {
      super(LafIconLookup.getIcon(NAME),
            LafIconLookup.getSelectedIcon(NAME),
            LafIconLookup.getDisabledIcon(NAME));
    }
  }

  private static LafManagerImpl ourTestInstance;
  @TestOnly
  public static LafManagerImpl getTestInstance() {
    if (ourTestInstance == null) {
      ourTestInstance = new LafManagerImpl();
    }
    return ourTestInstance;
  }
}
