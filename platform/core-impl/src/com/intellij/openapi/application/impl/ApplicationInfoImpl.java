// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.application.impl;

import com.google.common.annotations.VisibleForTesting;
import com.intellij.diagnostic.Activity;
import com.intellij.diagnostic.ParallelActivity;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.application.ApplicationNamesInfo;
import com.intellij.openapi.application.IdeUrlTrackingParametersProvider;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.application.ex.ApplicationInfoEx;
import com.intellij.openapi.application.ex.ProgressSlide;
import com.intellij.openapi.util.BuildNumber;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.JBColor;
import com.intellij.util.ArrayUtil;
import com.intellij.util.ArrayUtilRt;
import com.intellij.util.ObjectUtils;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.io.URLUtil;
import com.intellij.util.ui.JBImageIcon;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.net.URL;
import java.text.MessageFormat;
import java.util.List;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Provides access to content of *ApplicationInfo.xml file. Scheme for *ApplicationInfo.xml files is defined in platform/platform-resources/src/idea/ApplicationInfo.xsd,
 * so you need to update it when adding or removing support for some XML elements in this class.
 */
public final class ApplicationInfoImpl extends ApplicationInfoEx {
  private String myCodeName;
  private String myMajorVersion;
  private String myMinorVersion;
  private String myMicroVersion;
  private String myPatchVersion;
  private String myFullVersionFormat;
  private String myBuildNumber;
  private String myApiVersion;
  private String myVersionSuffix;
  private String myCompanyName = "JetBrains s.r.o.";
  private String myCopyrightStart = "2000";
  private String myShortCompanyName;
  private String myCompanyUrl = "https://www.jetbrains.com/";
  private Color myProgressColor;
  private Color myCopyrightForeground;
  private Color myAboutForeground;
  private Color myAboutLinkColor;
  private Rectangle myAboutLogoRect;
  private String myProgressTailIconName;
  private Icon myProgressTailIcon;
  private int myProgressHeight = 2;
  private int myProgressY = 350;
  private int myLicenseOffsetX = 114;
  private int myLicenseOffsetY = 85;
  private String mySplashImageUrl;
  private String myAboutImageUrl;
  @SuppressWarnings("UseJBColor") private Color mySplashTextColor = new Color(0, 35, 135);  // idea blue
  private String myIconUrl = "/icon.png";
  private String mySmallIconUrl = "/icon_small.png";
  private String myBigIconUrl;
  private String mySvgIconUrl;
  private String mySvgEapIconUrl;
  private String mySmallSvgIconUrl;
  private String mySmallSvgEapIconUrl;
  private String myToolWindowIconUrl = "/toolwindows/toolWindowProject.png";
  private String myWelcomeScreenLogoUrl;

  private Calendar myBuildDate;
  private Calendar myMajorReleaseBuildDate;
  private String myPackageCode;
  private boolean myShowLicensee = true;
  private String myCustomizeIDEWizardStepsProvider;
  private UpdateUrls myUpdateUrls;
  private String myDocumentationUrl;
  private String mySupportUrl;
  private String myYoutrackUrl;
  private String myFeedbackUrl;
  private String myPluginManagerUrl;
  private String myPluginsListUrl;
  private String myChannelsListUrl;
  private String myPluginsDownloadUrl;
  private String myBuiltinPluginsUrl;
  private String myWhatsNewUrl;
  private String myWinKeymapUrl;
  private String myMacKeymapUrl;
  private boolean myEAP;
  private boolean myHasHelp = true;
  private boolean myHasContextHelp = true;
  private String myWebHelpUrl = "https://www.jetbrains.com/idea/webhelp/";
  private String[] myEssentialPluginsIds;
  private String myFUStatisticsSettingsUrl;
  private String myEventLogSettingsUrl;
  private String myJetbrainsTvUrl;
  private String myEvalLicenseUrl = "https://www.jetbrains.com/store/license.html";
  private String myKeyConversionUrl = "https://www.jetbrains.com/shop/eform/keys-exchange";

  private String mySubscriptionFormId;
  private String mySubscriptionNewsKey;
  private String mySubscriptionNewsValue;
  private String mySubscriptionTipsKey;
  private boolean mySubscriptionTipsAvailable;
  private String mySubscriptionAdditionalFormData;
  private final List<ProgressSlide> myProgressSlides = new ArrayList<>();

  private static final String IDEA_PATH = "/idea/";
  private static final String ELEMENT_VERSION = "version";
  private static final String ATTRIBUTE_MAJOR = "major";
  private static final String ATTRIBUTE_MINOR = "minor";
  private static final String ATTRIBUTE_MICRO = "micro";
  private static final String ATTRIBUTE_PATCH = "patch";
  private static final String ATTRIBUTE_FULL = "full";
  private static final String ATTRIBUTE_CODENAME = "codename";
  private static final String ATTRIBUTE_NAME = "name";
  private static final String ELEMENT_BUILD = "build";
  private static final String ELEMENT_COMPANY = "company";
  private static final String ATTRIBUTE_NUMBER = "number";
  private static final String ATTRIBUTE_API_VERSION = "apiVersion";
  private static final String ATTRIBUTE_DATE = "date";
  private static final String ATTRIBUTE_MAJOR_RELEASE_DATE = "majorReleaseDate";
  private static final String ELEMENT_LOGO = "logo";
  private static final String ATTRIBUTE_URL = "url";
  private static final String COPYRIGHT_START = "copyrightStart";
  private static final String ATTRIBUTE_TEXT_COLOR = "textcolor";
  private static final String ATTRIBUTE_PROGRESS_COLOR = "progressColor";
  private static final String ATTRIBUTE_ABOUT_FOREGROUND_COLOR = "foreground";
  private static final String ATTRIBUTE_ABOUT_COPYRIGHT_FOREGROUND_COLOR = "copyrightForeground";
  private static final String ATTRIBUTE_ABOUT_LINK_COLOR = "linkColor";
  private static final String ATTRIBUTE_PROGRESS_HEIGHT = "progressHeight";
  private static final String ATTRIBUTE_PROGRESS_Y = "progressY";
  private static final String ATTRIBUTE_LICENSE_TEXT_OFFSET_X = "licenseOffsetX";
  private static final String ATTRIBUTE_LICENSE_TEXT_OFFSET_Y = "licenseOffsetY";
  private static final String ATTRIBUTE_PROGRESS_TAIL_ICON = "progressTailIcon";
  private static final String ELEMENT_ABOUT = "about";
  private static final String ELEMENT_ICON = "icon";
  private static final String ATTRIBUTE_SIZE32 = "size32";
  private static final String ATTRIBUTE_SIZE128 = "size128";
  private static final String ATTRIBUTE_SIZE16 = "size16";
  private static final String ATTRIBUTE_SIZE12 = "size12";
  private static final String ELEMENT_PACKAGE = "package";
  private static final String ATTRIBUTE_CODE = "code";
  private static final String ELEMENT_LICENSEE = "licensee";
  private static final String ATTRIBUTE_SHOW = "show";
  private static final String WELCOME_SCREEN_ELEMENT_NAME = "welcome-screen";
  private static final String LOGO_URL_ATTR = "logo-url";
  private static final String UPDATE_URLS_ELEMENT_NAME = "update-urls";
  private static final String XML_EXTENSION = ".xml";
  private static final String ATTRIBUTE_EAP = "eap";
  private static final String HELP_ELEMENT_NAME = "help";
  private static final String ELEMENT_DOCUMENTATION = "documentation";
  private static final String ELEMENT_SUPPORT = "support";
  private static final String ELEMENT_YOUTRACK = "youtrack";
  private static final String ELEMENT_FEEDBACK = "feedback";
  private static final String ELEMENT_PLUGINS = "plugins";
  private static final String ATTRIBUTE_LIST_URL = "list-url";
  private static final String ATTRIBUTE_CHANNEL_LIST_URL = "channel-list-url";
  private static final String ATTRIBUTE_DOWNLOAD_URL = "download-url";
  private static final String ATTRIBUTE_BUILTIN_URL = "builtin-url";
  private static final String ATTRIBUTE_WEBHELP_URL = "webhelp-url";
  private static final String ATTRIBUTE_HAS_HELP = "has-help";
  private static final String ATTRIBUTE_HAS_CONTEXT_HELP = "has-context-help";
  private static final String ELEMENT_WHATSNEW = "whatsnew";
  private static final String ELEMENT_KEYMAP = "keymap";
  private static final String ATTRIBUTE_WINDOWS_URL = "win";
  private static final String ATTRIBUTE_MAC_URL = "mac";
  private static final String ELEMENT_STATISTICS = "statistics";
  private static final String ATTRIBUTE_FU_STATISTICS_SETTINGS = "fus-settings";
  private static final String ATTRIBUTE_EVENT_LOG_STATISTICS_SETTINGS = "event-log-settings";
  private static final String ELEMENT_JB_TV = "jetbrains-tv";
  private static final String CUSTOMIZE_IDE_WIZARD_STEPS = "customize-ide-wizard";
  private static final String STEPS_PROVIDER = "provider";
  private static final String ELEMENT_EVALUATION = "evaluation";
  private static final String ATTRIBUTE_EVAL_LICENSE_URL = "license-url";
  private static final String ELEMENT_LICENSING = "licensing";
  private static final String ATTRIBUTE_KEY_CONVERSION_URL = "key-conversion-url";
  private static final String ESSENTIAL_PLUGIN = "essential-plugin";

  private static final String ELEMENT_SUBSCRIPTIONS = "subscriptions";
  private static final String ATTRIBUTE_SUBSCRIPTIONS_FORM_ID = "formid";
  private static final String ATTRIBUTE_SUBSCRIPTIONS_NEWS_KEY = "news-key";
  private static final String ATTRIBUTE_SUBSCRIPTIONS_NEWS_VALUE = "news-value";
  private static final String ATTRIBUTE_SUBSCRIPTIONS_TIPS_KEY = "tips-key";
  private static final String ATTRIBUTE_SUBSCRIPTIONS_TIPS_AVAILABLE = "tips-available";
  private static final String ATTRIBUTE_SUBSCRIPTIONS_ADDITIONAL_FORM_DATA = "additional-form-data";
  private static final String PROGRESS_SLIDE = "progressSlide";
  private static final String PROGRESS_PERCENT = "progressPercent";

  static final String DEFAULT_PLUGINS_HOST = "https://plugins.jetbrains.com";
  static final String IDEA_PLUGINS_HOST_PROPERTY = "idea.plugins.host";

  ApplicationInfoImpl() {
    String resource = IDEA_PATH + ApplicationNamesInfo.getComponentName() + XML_EXTENSION;
    try {
      loadState(JDOMUtil.load(ApplicationInfoImpl.class, resource));
    }
    catch (Exception e) {
      throw new RuntimeException("Cannot load resource: " + resource, e);
    }
  }

  @Override
  public Calendar getBuildDate() {
    return myBuildDate;
  }

  @Override
  public Calendar getMajorReleaseBuildDate() {
    return myMajorReleaseBuildDate != null ? myMajorReleaseBuildDate : myBuildDate;
  }

  @Override
  public BuildNumber getBuild() {
    return BuildNumber.fromString(myBuildNumber);
  }

  @Override
  public String getApiVersion() {
    BuildNumber build = getBuild();
    if (myApiVersion != null) {
      BuildNumber api = BuildNumber.fromStringWithProductCode(myApiVersion, build.getProductCode());
      if (api != null) {
        return api.asString();
      }
    }
    return build.asString();
  }

  @Override
  public String getMajorVersion() {
    return myMajorVersion;
  }

  @Override
  public String getMinorVersion() {
    return myMinorVersion;
  }

  @Override
  public String getMicroVersion() {
    return myMicroVersion;
  }

  @Override
  public String getPatchVersion() {
    return myPatchVersion;
  }

  @Override
  public String getFullVersion() {
    String result;
    if (myFullVersionFormat != null) {
      result = MessageFormat.format(myFullVersionFormat, myMajorVersion, myMinorVersion, myMicroVersion, myPatchVersion);
    }
    else {
      result = StringUtil.notNullize(myMajorVersion, "0") + '.' + StringUtil.notNullize(myMinorVersion, "0");
    }
    if (!StringUtil.isEmpty(myVersionSuffix)) {
      result += " " + myVersionSuffix;
    }
    return result;
  }

  @Override
  public String getStrictVersion() {
    return myMajorVersion + "." + myMinorVersion + "." + StringUtil.notNullize(myMicroVersion, "0") + "." + StringUtil.notNullize(myPatchVersion, "0");
  }

  @Override
  public String getVersionName() {
    String fullName = ApplicationNamesInfo.getInstance().getFullProductName();
    if (myEAP && !StringUtil.isEmptyOrSpaces(myCodeName)) fullName += " (" + myCodeName + ")";
    return fullName;
  }

  @Nullable
  @Override
  public String getHelpURL() {
    return null;
  }

  @Override
  public String getShortCompanyName() {
    return myShortCompanyName;
  }

  @Override
  public String getCompanyName() {
    return myCompanyName;
  }

  @Override
  public String getCompanyURL() {
    return IdeUrlTrackingParametersProvider.getInstance().augmentUrl(myCompanyUrl);
  }

  @Override
  public String getSplashImageUrl() {
    return mySplashImageUrl;
  }

  @Override
  public Color getSplashTextColor() {
    return mySplashTextColor;
  }

  @Override
  public String getAboutImageUrl() {
    return myAboutImageUrl;
  }

  @Override
  public Color getProgressColor() {
    return myProgressColor;
  }

  public Color getCopyrightForeground() {
    return ObjectUtils.notNull(myCopyrightForeground, JBColor.BLACK);
  }

  @Override
  public int getProgressHeight() {
    return myProgressHeight;
  }

  @Override
  public int getProgressY() {
    return myProgressY;
  }

  @Override
  public int getLicenseOffsetX() {
    return myLicenseOffsetX;
  }

  @Override
  public int getLicenseOffsetY() {
    return myLicenseOffsetY;
  }

  @Override
  @Nullable
  public Icon getProgressTailIcon() {
    if (myProgressTailIcon == null && myProgressTailIconName != null) {
      try {
        final URL url = getClass().getResource(myProgressTailIconName);
        @SuppressWarnings({"UnnecessaryFullyQualifiedName"}) final Image image = com.intellij.util.ImageLoader.loadFromUrl(url);
        if (image != null) {
          myProgressTailIcon = new JBImageIcon(image);
        }
      } catch (Exception ignore) {}
    }
    return myProgressTailIcon;
  }

  @Override
  public String getIconUrl() {
    return myIconUrl;
  }

  @NotNull
  @Override
  public String getSmallIconUrl() {
    return mySmallIconUrl;
  }

  @Override
  @Nullable
  public String getBigIconUrl() {
    return myBigIconUrl;
  }

  @Override
  @Nullable
  public String getApplicationSvgIconUrl() {
    return isEAP() && mySvgEapIconUrl != null ? mySvgEapIconUrl : mySvgIconUrl;
  }

  @Nullable
  @Override
  public String getSmallApplicationSvgIconUrl() {
    return isEAP() && mySmallSvgEapIconUrl != null ? mySmallSvgEapIconUrl : mySmallSvgIconUrl;
  }

  @Nullable
  @Override
  public File getApplicationSvgIconFile() {
    String svgIconUrl = getApplicationSvgIconUrl();
    if (svgIconUrl == null) return null;

    URL url = getClass().getResource(svgIconUrl);
    if (url != null && URLUtil.FILE_PROTOCOL.equals(url.getProtocol())) {
      return URLUtil.urlToFile(url);
    }
    return null;
  }

  @Override
  public String getToolWindowIconUrl() {
    return myToolWindowIconUrl;
  }

  @Override
  public String getWelcomeScreenLogoUrl() {
    return myWelcomeScreenLogoUrl;
  }

  @Nullable
  @Override
  public String getCustomizeIDEWizardStepsProvider() {
    return myCustomizeIDEWizardStepsProvider;
  }

  @Override
  public String getPackageCode() {
    return myPackageCode;
  }

  @Override
  public boolean isEAP() {
    return myEAP;
  }

  @Override
  public boolean isMajorEAP() {
    return myEAP && (myMinorVersion == null || myMinorVersion.indexOf('.') < 0);
  }

  @Override
  public UpdateUrls getUpdateUrls() {
    return myUpdateUrls;
  }

  @Override
  public String getDocumentationUrl() {
    return myDocumentationUrl;
  }

  @Override
  public String getSupportUrl() {
    return mySupportUrl;
  }

  @Override
  public String getYoutrackUrl() {
    return myYoutrackUrl;
  }

  @Override
  public String getFeedbackUrl() {
    return myFeedbackUrl;
  }

  @Override
  public String getPluginManagerUrl() {
    return myPluginManagerUrl;
  }

  @Override
  public boolean usesJetBrainsPluginRepository() {
    return DEFAULT_PLUGINS_HOST.equalsIgnoreCase(myPluginManagerUrl);
  }

  @Override
  public String getPluginsListUrl() {
    return myPluginsListUrl;
  }

  @Override
  public String getChannelsListUrl() {
    return myChannelsListUrl;
  }

  @Override
  public String getPluginsDownloadUrl() {
    return myPluginsDownloadUrl;
  }

  @Override
  public String getBuiltinPluginsUrl() {
    return myBuiltinPluginsUrl;
  }

  @Override
  public String getWebHelpUrl() {
    return myWebHelpUrl;
  }

  @Override
  public boolean hasHelp() {
    return myHasHelp;
  }

  @Override
  public boolean hasContextHelp() {
    return myHasContextHelp;
  }

  @Override
  public String getWhatsNewUrl() {
    return myWhatsNewUrl;
  }

  @Override
  public String getWinKeymapUrl() {
    return myWinKeymapUrl;
  }

  @Override
  public String getMacKeymapUrl() {
    return myMacKeymapUrl;
  }

  @Override
  public Color getAboutForeground() {
    return ObjectUtils.notNull(myAboutForeground, JBColor.BLACK);
  }

  @Nullable
  public Color getAboutLinkColor() {
    return myAboutLinkColor;
  }

  @Override
  public String getFullApplicationName() {
    return getVersionName() + " " + getFullVersion();
  }

  @Override
  public boolean showLicenseeInfo() {
    return myShowLicensee;
  }

  public String getCopyrightStart() {
    return myCopyrightStart;
  }

  public String getFUStatisticsSettingsUrl() {
    return myFUStatisticsSettingsUrl;
  }

  public String getEventLogSettingsUrl() {
    return myEventLogSettingsUrl;
  }

  @Override
  public String getJetbrainsTvUrl() {
    return myJetbrainsTvUrl;
  }

  @Override
  public String getEvalLicenseUrl() {
    return myEvalLicenseUrl;
  }

  @Override
  public String getKeyConversionUrl() {
    return myKeyConversionUrl;
  }

  @Override
  public Rectangle getAboutLogoRect() {
    return myAboutLogoRect;
  }

  @Override
  public String getSubscriptionFormId() {
    return mySubscriptionFormId;
  }

  @Override
  public String getSubscriptionNewsKey() {
    return mySubscriptionNewsKey;
  }

  @Override
  public String getSubscriptionNewsValue() {
    return mySubscriptionNewsValue;
  }

  @Override
  public String getSubscriptionTipsKey() {
    return mySubscriptionTipsKey;
  }

  @Override
  public boolean areSubscriptionTipsAvailable() {
    return mySubscriptionTipsAvailable;
  }

  @Nullable
  @Override
  public String getSubscriptionAdditionalFormData() {
    return mySubscriptionAdditionalFormData;
  }

  @Override
  public List<ProgressSlide> getProgressSlides() {
    return myProgressSlides;
  }

  private static volatile ApplicationInfoImpl ourShadowInstance;

  @NotNull
  public static ApplicationInfoEx getShadowInstance() {
    ApplicationInfoImpl result = ourShadowInstance;
    if (result != null) {
      return result;
    }

    //noinspection SynchronizeOnThis
    synchronized (ApplicationInfoImpl.class) {
      result = ourShadowInstance;
      if (result == null) {
        Activity activity = ParallelActivity.PREPARE_APP_INIT.start("load app info");
        result = new ApplicationInfoImpl();
        ourShadowInstance = result;
        activity.end();
      }
    }
    return result;
  }

  /**
   * Behavior of this method must be consistent with idea/ApplicationInfo.xsd schema.
   */
  private void loadState(Element parentNode) {
    Element versionElement = getChild(parentNode, ELEMENT_VERSION);
    if (versionElement != null) {
      myMajorVersion = versionElement.getAttributeValue(ATTRIBUTE_MAJOR);
      myMinorVersion = versionElement.getAttributeValue(ATTRIBUTE_MINOR);
      myMicroVersion = versionElement.getAttributeValue(ATTRIBUTE_MICRO);
      myPatchVersion = versionElement.getAttributeValue(ATTRIBUTE_PATCH);
      myFullVersionFormat = versionElement.getAttributeValue(ATTRIBUTE_FULL);
      myCodeName = versionElement.getAttributeValue(ATTRIBUTE_CODENAME);
      myEAP = Boolean.parseBoolean(versionElement.getAttributeValue(ATTRIBUTE_EAP));
      myVersionSuffix = versionElement.getAttributeValue("suffix");
      if (myVersionSuffix == null && myEAP) {
        myVersionSuffix = "EAP";
      }
    }

    Element companyElement = getChild(parentNode, ELEMENT_COMPANY);
    if (companyElement != null) {
      myCompanyName = companyElement.getAttributeValue(ATTRIBUTE_NAME, myCompanyName);
      myShortCompanyName = companyElement.getAttributeValue("shortName", shortenCompanyName(myCompanyName));
      myCompanyUrl = companyElement.getAttributeValue(ATTRIBUTE_URL, myCompanyUrl);
      myCopyrightStart = companyElement.getAttributeValue(COPYRIGHT_START, myCopyrightStart);
    }

    Element buildElement = getChild(parentNode, ELEMENT_BUILD);
    if (buildElement != null) {
      myBuildNumber = buildElement.getAttributeValue(ATTRIBUTE_NUMBER);
      myApiVersion = buildElement.getAttributeValue(ATTRIBUTE_API_VERSION);
      setBuildNumber(myApiVersion, myBuildNumber);

      String dateString = buildElement.getAttributeValue(ATTRIBUTE_DATE);
      if ("__BUILD_DATE__".equals(dateString)) {
        myBuildDate = new GregorianCalendar();
        try (JarFile bootstrapJar = new JarFile(PathManager.getHomePath() + "/lib/bootstrap.jar")) {
          JarEntry jarEntry = bootstrapJar.entries().nextElement();  // META-INF is always updated on build
          myBuildDate.setTime(new Date(jarEntry.getTime()));
        }
        catch (Exception ignore) { }
      }
      else {
        myBuildDate = parseDate(dateString);
      }
      String majorReleaseDateString = buildElement.getAttributeValue(ATTRIBUTE_MAJOR_RELEASE_DATE);
      if (majorReleaseDateString != null) {
        myMajorReleaseBuildDate = parseDate(majorReleaseDateString);
      }
    }

    Element logoElement = getChild(parentNode, ELEMENT_LOGO);
    if (logoElement != null) {
      mySplashImageUrl = logoElement.getAttributeValue(ATTRIBUTE_URL);
      mySplashTextColor = parseColor(logoElement.getAttributeValue(ATTRIBUTE_TEXT_COLOR));
      String v = logoElement.getAttributeValue(ATTRIBUTE_PROGRESS_COLOR);
      if (v != null) {
        myProgressColor = parseColor(v);
      }

      v = logoElement.getAttributeValue(ATTRIBUTE_PROGRESS_TAIL_ICON);
      if (v != null) {
        myProgressTailIconName = v;
      }

      v = logoElement.getAttributeValue(ATTRIBUTE_PROGRESS_HEIGHT);
      if (v != null) {
        myProgressHeight = Integer.parseInt(v);
      }

      v = logoElement.getAttributeValue(ATTRIBUTE_PROGRESS_Y);
      if (v != null) {
        myProgressY = Integer.parseInt(v);
      }

      v = logoElement.getAttributeValue(ATTRIBUTE_LICENSE_TEXT_OFFSET_X);
      if (v != null) {
        myLicenseOffsetX = Integer.parseInt(v);
      }

      v = logoElement.getAttributeValue(ATTRIBUTE_LICENSE_TEXT_OFFSET_Y);
      if (v != null) {
        myLicenseOffsetY = Integer.parseInt(v);
      }

      for (Element child : getChildren(logoElement, PROGRESS_SLIDE)) {
        String slideUrl = child.getAttributeValue(ATTRIBUTE_URL);
        assert slideUrl != null;
        String progressPercentString = child.getAttributeValue(PROGRESS_PERCENT);
        assert progressPercentString != null;

        int progressPercentInt = Integer.parseInt(progressPercentString);
        assert (progressPercentInt <= 100 && progressPercentInt >= 0);

        float progressPercentFloat = (float) progressPercentInt / 100;
        ProgressSlide progressSlide = new ProgressSlide(slideUrl, progressPercentFloat);
        myProgressSlides.add(progressSlide);
      }
    }

    Element aboutLogoElement = getChild(parentNode, ELEMENT_ABOUT);
    if (aboutLogoElement != null) {
      myAboutImageUrl = aboutLogoElement.getAttributeValue(ATTRIBUTE_URL);

      String v = aboutLogoElement.getAttributeValue(ATTRIBUTE_ABOUT_FOREGROUND_COLOR);
      if (v != null) {
        myAboutForeground = parseColor(v);
      }
      v = aboutLogoElement.getAttributeValue(ATTRIBUTE_ABOUT_COPYRIGHT_FOREGROUND_COLOR);
      if (v != null) {
        myCopyrightForeground = parseColor(v);
      }

      String c = aboutLogoElement.getAttributeValue(ATTRIBUTE_ABOUT_LINK_COLOR);
      if (c != null) {
        myAboutLinkColor = parseColor(c);
      }

      String logoX = aboutLogoElement.getAttributeValue("logoX");
      String logoY = aboutLogoElement.getAttributeValue("logoY");
      String logoW = aboutLogoElement.getAttributeValue("logoW");
      String logoH = aboutLogoElement.getAttributeValue("logoH");
      if (logoX != null && logoY != null && logoW != null && logoH != null) {
        try {
          myAboutLogoRect = new Rectangle(Integer.parseInt(logoX), Integer.parseInt(logoY), Integer.parseInt(logoW), Integer.parseInt(logoH));
        }
        catch (NumberFormatException ignored) { }
      }
    }

    Element iconElement = getChild(parentNode, ELEMENT_ICON);
    if (iconElement != null) {
      myIconUrl = iconElement.getAttributeValue(ATTRIBUTE_SIZE32);
      mySmallIconUrl = iconElement.getAttributeValue(ATTRIBUTE_SIZE16, mySmallIconUrl);
      myBigIconUrl = iconElement.getAttributeValue(ATTRIBUTE_SIZE128, (String)null);
      final String toolWindowIcon = iconElement.getAttributeValue(ATTRIBUTE_SIZE12);
      if (toolWindowIcon != null) {
        myToolWindowIconUrl = toolWindowIcon;
      }
      mySvgIconUrl = iconElement.getAttributeValue("svg");
      mySmallSvgIconUrl = iconElement.getAttributeValue("svg-small");
    }
    Element iconEap = getChild(parentNode, "icon-eap");
    if (iconEap != null) {
      mySvgEapIconUrl = iconEap.getAttributeValue("svg");
      mySmallSvgEapIconUrl = iconEap.getAttributeValue("svg-small");
    }

    Element packageElement = getChild(parentNode, ELEMENT_PACKAGE);
    if (packageElement != null) {
      myPackageCode = packageElement.getAttributeValue(ATTRIBUTE_CODE);
    }

    Element showLicensee = getChild(parentNode, ELEMENT_LICENSEE);
    if (showLicensee != null) {
      myShowLicensee = Boolean.valueOf(showLicensee.getAttributeValue(ATTRIBUTE_SHOW)).booleanValue();
    }

    Element welcomeScreen = getChild(parentNode, WELCOME_SCREEN_ELEMENT_NAME);
    if (welcomeScreen != null) {
      myWelcomeScreenLogoUrl = welcomeScreen.getAttributeValue(LOGO_URL_ATTR);
    }

    Element wizardSteps = getChild(parentNode, CUSTOMIZE_IDE_WIZARD_STEPS);
    if (wizardSteps != null) {
      myCustomizeIDEWizardStepsProvider = wizardSteps.getAttributeValue(STEPS_PROVIDER);
    }

    Element helpElement = getChild(parentNode, HELP_ELEMENT_NAME);
    if (helpElement != null) {
      final String webHelpUrl = helpElement.getAttributeValue(ATTRIBUTE_WEBHELP_URL);
      if (webHelpUrl != null) {
        myWebHelpUrl = webHelpUrl;
      }

      String attValue = helpElement.getAttributeValue(ATTRIBUTE_HAS_HELP);
      myHasHelp = attValue == null || Boolean.parseBoolean(attValue); // Default is true

      attValue = helpElement.getAttributeValue(ATTRIBUTE_HAS_CONTEXT_HELP);
      myHasContextHelp = attValue == null || Boolean.parseBoolean(attValue); // Default is true
    }

    Element updateUrls = getChild(parentNode, UPDATE_URLS_ELEMENT_NAME);
    myUpdateUrls = new UpdateUrlsImpl(updateUrls);

    Element documentationElement = getChild(parentNode, ELEMENT_DOCUMENTATION);
    if (documentationElement != null) {
      myDocumentationUrl = documentationElement.getAttributeValue(ATTRIBUTE_URL);
    }

    Element supportElement = getChild(parentNode, ELEMENT_SUPPORT);
    if (supportElement != null) {
      mySupportUrl = supportElement.getAttributeValue(ATTRIBUTE_URL);
    }

    Element youtrackElement = getChild(parentNode, ELEMENT_YOUTRACK);
    if (youtrackElement != null) {
      myYoutrackUrl = youtrackElement.getAttributeValue(ATTRIBUTE_URL);
    }

    Element feedbackElement = getChild(parentNode, ELEMENT_FEEDBACK);
    if (feedbackElement != null) {
      myFeedbackUrl = feedbackElement.getAttributeValue(ATTRIBUTE_URL);
    }

    Element whatsnewElement = getChild(parentNode, ELEMENT_WHATSNEW);
    if (whatsnewElement != null) {
      myWhatsNewUrl = whatsnewElement.getAttributeValue(ATTRIBUTE_URL);
    }

    Element pluginsElement = getChild(parentNode, ELEMENT_PLUGINS);
    if (pluginsElement != null) {
      String url = pluginsElement.getAttributeValue(ATTRIBUTE_URL);
      if (url != null) {
        myPluginManagerUrl = StringUtil.trimEnd(url, "/");
      }

      String listUrl = pluginsElement.getAttributeValue(ATTRIBUTE_LIST_URL);
      if (listUrl != null) {
        myPluginsListUrl = listUrl;
      }

      String channelListUrl = pluginsElement.getAttributeValue(ATTRIBUTE_CHANNEL_LIST_URL);
      if (channelListUrl != null) {
        myChannelsListUrl = channelListUrl;
      }

      String downloadUrl = pluginsElement.getAttributeValue(ATTRIBUTE_DOWNLOAD_URL);
      if (downloadUrl != null) {
        myPluginsDownloadUrl = downloadUrl;
      }

      if (!getBuild().isSnapshot()) {
        myBuiltinPluginsUrl = StringUtil.nullize(pluginsElement.getAttributeValue(ATTRIBUTE_BUILTIN_URL));
      }
    }

    final String pluginsHost = System.getProperty(IDEA_PLUGINS_HOST_PROPERTY);
    if (pluginsHost != null) {
      myPluginManagerUrl = StringUtil.trimEnd(pluginsHost, "/");
      myPluginsListUrl = myChannelsListUrl = myPluginsDownloadUrl = null;
    }

    myPluginManagerUrl = ObjectUtils.coalesce(myPluginManagerUrl, DEFAULT_PLUGINS_HOST);
    myPluginsListUrl = ObjectUtils.coalesce(myPluginsListUrl, myPluginManagerUrl + "/plugins/list/");
    myChannelsListUrl = ObjectUtils.coalesce(myChannelsListUrl, myPluginManagerUrl + "/channels/list/");
    myPluginsDownloadUrl = ObjectUtils.coalesce(myPluginsDownloadUrl, myPluginManagerUrl + "/pluginManager/");

    Element keymapElement = getChild(parentNode, ELEMENT_KEYMAP);
    if (keymapElement != null) {
      myWinKeymapUrl = keymapElement.getAttributeValue(ATTRIBUTE_WINDOWS_URL);
      myMacKeymapUrl = keymapElement.getAttributeValue(ATTRIBUTE_MAC_URL);
    }

    List<Element> essentialPluginsElements = getChildren(parentNode, ESSENTIAL_PLUGIN);
    Collection<String> essentialPluginsIds = ContainerUtil.mapNotNull(essentialPluginsElements, element -> {
      String id = element.getTextTrim();
      return StringUtil.isNotEmpty(id) ? id : null;
    });
    myEssentialPluginsIds = ArrayUtilRt.toStringArray(essentialPluginsIds);

    Element statisticsElement = getChild(parentNode, ELEMENT_STATISTICS);
    if (statisticsElement != null) {
      myFUStatisticsSettingsUrl = statisticsElement.getAttributeValue(ATTRIBUTE_FU_STATISTICS_SETTINGS);
      myEventLogSettingsUrl = statisticsElement.getAttributeValue(ATTRIBUTE_EVENT_LOG_STATISTICS_SETTINGS);
    }
    else {
      myFUStatisticsSettingsUrl = "https://www.jetbrains.com/idea/statistics/fus-assistant.xml";
      myEventLogSettingsUrl = "https://resources.jetbrains.com/storage/fus/config/%s/lion-v3-assistant.xml";
    }

    Element tvElement = getChild(parentNode, ELEMENT_JB_TV);
    if (tvElement != null) {
      myJetbrainsTvUrl = tvElement.getAttributeValue(ATTRIBUTE_URL);
    }

    Element evaluationElement = getChild(parentNode, ELEMENT_EVALUATION);
    if (evaluationElement != null) {
      final String url = evaluationElement.getAttributeValue(ATTRIBUTE_EVAL_LICENSE_URL);
      if (url != null && !url.isEmpty()) {
        myEvalLicenseUrl = url.trim();
      }
    }

    Element licensingElement = getChild(parentNode, ELEMENT_LICENSING);
    if (licensingElement != null) {
      final String url = licensingElement.getAttributeValue(ATTRIBUTE_KEY_CONVERSION_URL);
      if (url != null && !url.isEmpty()) {
        myKeyConversionUrl = url.trim();
      }
    }

    Element subscriptionsElement = getChild(parentNode, ELEMENT_SUBSCRIPTIONS);
    if (subscriptionsElement != null) {
      mySubscriptionFormId = subscriptionsElement.getAttributeValue(ATTRIBUTE_SUBSCRIPTIONS_FORM_ID);
      mySubscriptionNewsKey = subscriptionsElement.getAttributeValue(ATTRIBUTE_SUBSCRIPTIONS_NEWS_KEY);
      mySubscriptionNewsValue = subscriptionsElement.getAttributeValue(ATTRIBUTE_SUBSCRIPTIONS_NEWS_VALUE, "yes");
      mySubscriptionTipsKey = subscriptionsElement.getAttributeValue(ATTRIBUTE_SUBSCRIPTIONS_TIPS_KEY);
      mySubscriptionTipsAvailable = Boolean.parseBoolean(subscriptionsElement.getAttributeValue(ATTRIBUTE_SUBSCRIPTIONS_TIPS_AVAILABLE));
      mySubscriptionAdditionalFormData = subscriptionsElement.getAttributeValue(ATTRIBUTE_SUBSCRIPTIONS_ADDITIONAL_FORM_DATA);
    }
  }

  @NotNull
  private static List<Element> getChildren(Element parentNode, String name) {
    return parentNode.getChildren(name, parentNode.getNamespace());
  }

  private static Element getChild(Element parentNode, String name) {
    return parentNode.getChild(name, parentNode.getNamespace());
  }

  //copy of ApplicationInfoProperties.shortenCompanyName
  @VisibleForTesting
  static String shortenCompanyName(String name) {
    return StringUtil.trimEnd(StringUtil.trimEnd(name, " s.r.o."), " Inc.");
  }

  private static void setBuildNumber(String apiVersion, String buildNumber) {
    PluginManagerCore.BUILD_NUMBER = apiVersion != null ? apiVersion : buildNumber;
  }

  private static GregorianCalendar parseDate(final String dateString) {
    GregorianCalendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
    try {
      calendar.set(Calendar.YEAR, Integer.parseInt(dateString.substring(0, 4)));
      calendar.set(Calendar.MONTH, Integer.parseInt(dateString.substring(4, 6)) - 1);
      calendar.set(Calendar.DAY_OF_MONTH, Integer.parseInt(dateString.substring(6, 8)));
      if (dateString.length() > 8) {
        calendar.set(Calendar.HOUR_OF_DAY, Integer.parseInt(dateString.substring(8, 10)));
        calendar.set(Calendar.MINUTE, Integer.parseInt(dateString.substring(10, 12)));
      }
      else {
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
      }
    }
    catch (Exception ignore) { }
    return calendar;
  }

  @SuppressWarnings("UseJBColor")
  private static Color parseColor(final String colorString) {
    final long rgb = Long.parseLong(colorString, 16);
    return new Color((int)rgb, rgb > 0xffffff);
  }

  @Override
  public boolean isEssentialPlugin(@NotNull String pluginId) {
    return PluginManagerCore.CORE_PLUGIN_ID.equals(pluginId) || ArrayUtil.contains(pluginId, myEssentialPluginsIds);
  }

  public List<String> getEssentialPluginsIds() {
    return ContainerUtil.immutableList(myEssentialPluginsIds);
  }

  private static class UpdateUrlsImpl implements UpdateUrls {
    private String myCheckingUrl;
    private String myPatchesUrl;

    private UpdateUrlsImpl(Element element) {
      if (element != null) {
        myCheckingUrl = element.getAttributeValue("check");
        myPatchesUrl = element.getAttributeValue("patches");
      }
    }

    @Override
    public String getCheckingUrl() {
      return myCheckingUrl;
    }

    @Override
    public String getPatchesUrl() {
      return myPatchesUrl;
    }
  }

  private static volatile boolean myInStressTest;
  public static boolean isInStressTest() {
    return myInStressTest;
  }
  @TestOnly
  public static void setInStressTest(boolean inStressTest) {
    myInStressTest = inStressTest;
  }
}