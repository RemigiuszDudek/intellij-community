// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.github.ui.cloneDialog

import com.intellij.dvcs.repo.ClonePathProvider
import com.intellij.dvcs.ui.CloneDvcsValidationUtils
import com.intellij.dvcs.ui.DvcsBundle.getString
import com.intellij.dvcs.ui.SelectChildTextFieldWithBrowseButton
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.rd.attachChild
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.ui.cloneDialog.VcsCloneDialogExtensionComponent
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.*
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.components.panels.Wrapper
import com.intellij.ui.layout.*
import com.intellij.ui.speedSearch.NameFilteringListModel
import com.intellij.ui.speedSearch.SpeedSearch
import com.intellij.util.IconUtil
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.progress.ProgressVisibilityManager
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.JBValue
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.cloneDialog.AccountMenuItem
import com.intellij.util.ui.cloneDialog.AccountMenuItem.Account
import com.intellij.util.ui.cloneDialog.AccountMenuItem.Action
import com.intellij.util.ui.cloneDialog.AccountMenuPopupStep
import com.intellij.util.ui.cloneDialog.AccountsMenuListPopup
import com.intellij.util.ui.cloneDialog.VcsCloneDialogUiSpec
import git4idea.GitUtil
import git4idea.checkout.GitCheckoutProvider
import git4idea.commands.Git
import git4idea.remote.GitRememberedInputs
import icons.GithubIcons
import org.jetbrains.plugins.github.api.*
import org.jetbrains.plugins.github.api.data.GithubAuthenticatedUser
import org.jetbrains.plugins.github.api.data.GithubRepo
import org.jetbrains.plugins.github.api.data.request.Affiliation
import org.jetbrains.plugins.github.api.data.request.GithubRequestPagination
import org.jetbrains.plugins.github.api.util.GithubApiPagesLoader
import org.jetbrains.plugins.github.authentication.GithubAuthenticationManager
import org.jetbrains.plugins.github.authentication.accounts.*
import org.jetbrains.plugins.github.authentication.ui.GithubLoginPanel
import org.jetbrains.plugins.github.exceptions.GithubMissingTokenException
import org.jetbrains.plugins.github.pullrequest.avatars.CachingGithubAvatarIconsProvider
import org.jetbrains.plugins.github.util.CachingGithubUserAvatarLoader
import org.jetbrains.plugins.github.util.GithubImageResizer
import org.jetbrains.plugins.github.util.GithubUrlUtil
import org.jetbrains.plugins.github.util.handleOnEdt
import java.awt.FlowLayout
import java.awt.event.ActionListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Paths
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.event.DocumentEvent
import kotlin.properties.Delegates

internal class GHCloneDialogExtensionComponent(
  private val project: Project,
  private val authenticationManager: GithubAuthenticationManager,
  private val executorManager: GithubApiRequestExecutorManager,
  private val apiExecutorFactory: GithubApiRequestExecutor.Factory,
  private val accountInformationProvider: GithubAccountInformationProvider,
  private val avatarLoader: CachingGithubUserAvatarLoader,
  private val imageResizer: GithubImageResizer
) : VcsCloneDialogExtensionComponent() {
  private val LOG = logger<GHCloneDialogExtensionComponent>()

  private val progressManager: ProgressVisibilityManager

  // UI
  private val defaultAvatar = resizeIcon(GithubIcons.DefaultAvatar, VcsCloneDialogUiSpec.Components.avatarSize)
  private val defaultPopupAvatar = resizeIcon(GithubIcons.DefaultAvatar, VcsCloneDialogUiSpec.Components.popupMenuAvatarSize)
  private val avatarSizeUiInt = JBValue.UIInteger("GHCloneDialogExtensionComponent.popupAvatarSize",
                                                  VcsCloneDialogUiSpec.Components.popupMenuAvatarSize)


  private val wrapper: Wrapper = Wrapper()
  private val repositoriesPanel: DialogPanel
  private val repositoryList: GHRepositoryList

  private val popupMenuMouseAdapter = object : MouseAdapter() {
    override fun mouseClicked(e: MouseEvent?) = showPopupMenu()
  }

  private val accountsPanel: JPanel = JPanel(FlowLayout(FlowLayout.LEADING, JBUI.scale(1), 0)).apply {
    addMouseListener(popupMenuMouseAdapter)
  }

  private val searchField: SearchTextField = SearchTextField(false).apply {
    textEditor.emptyText.appendText("Search or enter a GitHub repository URL")
  }
  private val directoryField = SelectChildTextFieldWithBrowseButton(
    ClonePathProvider.defaultParentDirectoryPath(project, GitRememberedInputs.getInstance())).apply {
    val fcd = FileChooserDescriptorFactory.createSingleFolderDescriptor()
    fcd.isShowFileSystemRoots = true
    fcd.isHideIgnored = false
    addBrowseFolderListener(getString("clone.destination.directory.browser.title"),
                            getString("clone.destination.directory.browser.description"),
                            project,
                            fcd)
  }

  // state
  private val userDetailsByAccount = hashMapOf<GithubAccount, GithubAuthenticatedUser>()
  private val repositoriesByAccount = hashMapOf<GithubAccount, LinkedHashSet<GithubRepo>>()
  private val errorsByAccount = hashMapOf<GithubAccount, GHRepositoryListItem.Error>()
  private val originListModel = CollectionListModel<GHRepositoryListItem>()
  private var inLoginState = false
  private var selectedUrl by Delegates.observable<String?>(null) { _, _, _ -> onSelectedUrlChanged() }

  // popup menu
  private val accountComponents = hashMapOf<GithubAccount, JLabel>()
  private val avatarsByAccount = hashMapOf<GithubAccount, Icon>()

  init {
    val speedSearch = SpeedSearch()

    val filteringListModel = NameFilteringListModel<GHRepositoryListItem>(originListModel,
                                                                          { it.stringToSearch },
                                                                          speedSearch::shouldBeShowing,
                                                                          { speedSearch.filter ?: "" }
    ).apply {
      setFilter { item ->
        item != null && when (item) {
          is GHRepositoryListItem.Repo -> speedSearch.shouldBeShowing(item.repo.fullName)
          else -> speedSearch.filter.isEmpty()
        }
      }
    }

    searchField.addDocumentListener(object : DocumentAdapter() {
      override fun textChanged(e: DocumentEvent) {
        speedSearch.updatePattern(searchField.text)
        filteringListModel.refilter()
        updateSelectedUrl()
      }
    })

    repositoryList = GHRepositoryList(filteringListModel).apply {
      addListSelectionListener {
        if (it.valueIsAdjusting) return@addListSelectionListener
        updateSelectedUrl()
      }
    }

    progressManager = object : ProgressVisibilityManager() {
      override fun setProgressVisible(visible: Boolean) = repositoryList.setPaintBusy(visible)

      override fun getModalityState() = ModalityState.any()
    }

    this.attachChild(progressManager)

    ApplicationManager.getApplication().messageBus.connect(this).apply {
      subscribe(GithubAccountManager.ACCOUNT_REMOVED_TOPIC, object : AccountRemovedListener {
        override fun accountRemoved(removedAccount: GithubAccount) {
          removeAccount(removedAccount)
          dialogStateListener.onListItemChanged()
        }
      })

      subscribe(GithubAccountManager.ACCOUNT_TOKEN_CHANGED_TOPIC, object : AccountTokenChangedListener {
        override fun tokenChanged(account: GithubAccount) {
          if (repositoriesByAccount[account] != null)
            return
          dialogStateListener.onListItemChanged()
          addAccount(account)
          switchToRepositories()
        }
      })
    }

    repositoriesPanel = panel {
      row {
        cell(isFullWidth = true) {
          searchField(pushX, growX)
          JSeparator(JSeparator.VERTICAL)(growY)
          accountsPanel()
        }
      }
      row {
        ScrollPaneFactory.createScrollPane(repositoryList)(push, grow)
      }
      row("Directory:") {
        directoryField(growX, pushX)
      }
    }
    repositoriesPanel.border = JBUI.Borders.empty(UIUtil.REGULAR_PANEL_TOP_BOTTOM_INSET, UIUtil.REGULAR_PANEL_LEFT_RIGHT_INSET)


    if (authenticationManager.hasAccounts()) {
      switchToRepositories()
      authenticationManager.getAccounts().forEach(this@GHCloneDialogExtensionComponent::addAccount)
    }
    else {
      switchToLogin()
    }
  }

  private fun switchToLogin(account: GithubAccount? = null) {
    val errorPanel = JPanel(VerticalLayout(10))
    val githubLoginPanel = buildGitHubLoginPanel(account, errorPanel)
    val loginPanel = JBUI.Panels.simplePanel()
      .addToTop(githubLoginPanel)
      .addToCenter(errorPanel)
    wrapper.setContent(loginPanel)
    wrapper.repaint()
    inLoginState = true
    updateSelectedUrl()
  }

  private fun switchToRepositories() {
    wrapper.setContent(repositoriesPanel)
    wrapper.repaint()
    inLoginState = false
    updateSelectedUrl()
  }

  private fun addAccount(account: GithubAccount) {
    repositoriesByAccount.remove(account)

    val label = accountComponents.getOrPut(account) {
      JLabel().apply {
        icon = defaultAvatar
        toolTipText = account.name
        isOpaque = false
        addMouseListener(popupMenuMouseAdapter)
      }
    }
    accountsPanel.add(label)

    try {
      val executor = executorManager.getExecutor(account)
      loadUserDetails(account, executor)
      loadRepositories(account, executor)
    }
    catch (e: GithubMissingTokenException) {
      errorsByAccount[account] = GHRepositoryListItem.Error(account,
                                                            "Missing access token",
                                                            "Log in",
                                                            Runnable { switchToLogin(account) })
      refillRepositories()
    }
  }

  private fun removeAccount(account: GithubAccount) {
    repositoriesByAccount.remove(account)
    accountComponents.remove(account).let {
      accountsPanel.remove(it)
      accountsPanel.revalidate()
      accountsPanel.repaint()
    }
    refillRepositories()
    if (!authenticationManager.hasAccounts()) switchToLogin()
  }

  private fun loadUserDetails(account: GithubAccount,
                              executor: GithubApiRequestExecutor.WithTokenAuth) {
    progressManager.run(object : Task.Backgroundable(project, "Not Visible") {
      lateinit var user: GithubAuthenticatedUser
      lateinit var iconProvider: CachingGithubAvatarIconsProvider

      override fun run(indicator: ProgressIndicator) {
        user = accountInformationProvider.getInformation(executor, indicator, account)
        iconProvider = CachingGithubAvatarIconsProvider
          .Factory(avatarLoader, imageResizer, executor)
          .create(avatarSizeUiInt, accountsPanel)
      }

      override fun onSuccess() {
        userDetailsByAccount[account] = user
        val avatar = iconProvider.getIcon(user.avatarUrl)
        avatarsByAccount[account] = avatar
        accountComponents[account]?.icon = resizeIcon(avatar, VcsCloneDialogUiSpec.Components.avatarSize)
        refillRepositories()
      }

      override fun onThrowable(error: Throwable) {
        LOG.error(error)
        errorsByAccount[account] = GHRepositoryListItem.Error(account,
                                                              "Unable to load repositories",
                                                              "Retry",
                                                              Runnable { addAccount(account) })
      }
    })
  }

  private fun loadRepositories(account: GithubAccount,
                               executor: GithubApiRequestExecutor.WithTokenAuth) {
    repositoriesByAccount.remove(account)
    errorsByAccount.remove(account)

    progressManager.run(object : Task.Backgroundable(project, "Not Visible") {
      override fun run(indicator: ProgressIndicator) {
        val repoPagesRequest = GithubApiRequests.CurrentUser.Repos.pages(account.server,
                                                                         affiliation = Affiliation.combine(Affiliation.OWNER,
                                                                                                           Affiliation.COLLABORATOR),
                                                                         pagination = GithubRequestPagination.DEFAULT)
        val pageItemsConsumer: (List<GithubRepo>) -> Unit = {
          runInEdt {
            repositoriesByAccount.getOrPut(account, { UpdateOrderLinkedHashSet() }).addAll(it)
            refillRepositories()
          }
        }
        GithubApiPagesLoader.loadAll(executor, indicator, repoPagesRequest, pageItemsConsumer)

        val orgsRequest = GithubApiRequests.CurrentUser.Orgs.pages(account.server)
        val userOrganizations = GithubApiPagesLoader.loadAll(executor, indicator, orgsRequest).sortedBy { it.login }

        for (org in userOrganizations) {
          val orgRepoRequest = GithubApiRequests.Organisations.Repos.pages(account.server, org.login, GithubRequestPagination.DEFAULT)
          GithubApiPagesLoader.loadAll(executor, indicator, orgRepoRequest, pageItemsConsumer)
        }
      }

      override fun onThrowable(error: Throwable) {
        LOG.error(error)
        errorsByAccount[account] = GHRepositoryListItem.Error(account,
                                                              "Unable to load repositories",
                                                              "Retry",
                                                              Runnable { loadRepositories(account, executor) })
      }
    })
  }

  private fun refillRepositories() {
    val selectedValue = repositoryList.selectedValue
    originListModel.removeAll()
    for (account in authenticationManager.getAccounts()) {
      if (errorsByAccount[account] != null) {
        originListModel.add(errorsByAccount[account])
      }
      val user = userDetailsByAccount[account] ?: continue
      val repos = repositoriesByAccount[account] ?: continue
      for (repo in repos) {
        originListModel.add(GHRepositoryListItem.Repo(account, user, repo))
      }
    }
    repositoryList.setSelectedValue(selectedValue, false)
  }

  override fun getView() = wrapper

  override fun doValidateAll(): List<ValidationInfo> {
    val list = ArrayList<ValidationInfo>()
    ContainerUtil.addIfNotNull(list, CloneDvcsValidationUtils.checkDirectory(directoryField.text, directoryField.textField))
    ContainerUtil.addIfNotNull(list, CloneDvcsValidationUtils.createDestination(directoryField.text))
    return list
  }

  override fun doClone() {
    val parent = Paths.get(directoryField.text).toAbsolutePath().parent

    val lfs = LocalFileSystem.getInstance()
    var destinationParent = lfs.findFileByIoFile(parent.toFile())
    if (destinationParent == null) {
      destinationParent = lfs.refreshAndFindFileByIoFile(parent.toFile())
    }
    if (destinationParent == null) {
      return
    }
    val directoryName = Paths.get(directoryField.text).fileName.toString()
    val parentDirectory = parent.toAbsolutePath().toString()

    GitCheckoutProvider.clone(project, Git.getInstance(), ProjectLevelVcsManager.getInstance(project).compositeCheckoutListener,
                              destinationParent, selectedUrl, directoryName, parentDirectory)
  }

  override fun onComponentSelected() {
    dialogStateListener.onOkActionNameChanged("Clone")
    updateSelectedUrl()
  }

  private fun buildGitHubLoginPanel(account: GithubAccount?,
                                    errorPanel: JPanel): GithubLoginPanel {
    val alwaysUnique: (name: String, server: GithubServerPath) -> Boolean = { _, _ -> true }
    return GithubLoginPanel(
      apiExecutorFactory,
      if (account == null) authenticationManager::isAccountUnique else alwaysUnique,
      project,
      false
    ).apply {
      if (account != null) {
        setCredentials(account.name, null, false)
        setServer(account.server.toUrl(), false)
      }

      setLoginListener(ActionListener {
        acquireLoginAndToken(EmptyProgressIndicator(ModalityState.stateForComponent(this)))
          .handleOnEdt { loginToken, throwable ->
            errorPanel.removeAll()
            if (throwable != null) {
              for (validationInfo in doValidateAll()) {
                val component = SimpleColoredComponent()
                component.append(validationInfo.message, SimpleTextAttributes.ERROR_ATTRIBUTES)
                errorPanel.add(component)
                errorPanel.revalidate()
              }
              errorPanel.repaint()
            }
            if (loginToken != null) {
              val login = loginToken.first
              val token = loginToken.second
              if (account != null) {
                authenticationManager.updateAccountToken(account, token)
              }
              else {
                authenticationManager.registerAccount(login, getServer().host, token)
              }
            }
          }
      })
      setCancelListener(ActionListener { switchToRepositories() })
      setLoginButtonVisible(true)
      setCancelButtonVisible(authenticationManager.hasAccounts())
    }
  }

  private fun updateSelectedUrl() {
    repositoryList.emptyText.clear()
    if (inLoginState) {
      selectedUrl = null
      return
    }
    val githubRepoPath = getGithubRepoPath(searchField.text)
    if (githubRepoPath != null) {
      selectedUrl = githubRepoPath.toUrl()
      repositoryList.emptyText.appendText("Clone '$githubRepoPath'")
      return
    }
    val selectedValue = repositoryList.selectedValue
    if (selectedValue is GHRepositoryListItem.Repo) {
      selectedUrl = selectedValue.repo.cloneUrl
      return
    }
    selectedUrl = null
  }

  private fun getGithubRepoPath(url: String): GHRepositoryCoordinates? {
    try {
      if (!url.endsWith(GitUtil.DOT_GIT, true)) return null

      var serverPath = GithubServerPath.from(url)
      serverPath = GithubServerPath.from(serverPath.toUrl().removeSuffix(serverPath.suffix ?: ""))

      val githubFullPath = GithubUrlUtil.getUserAndRepositoryFromRemoteUrl(url) ?: return null
      return GHRepositoryCoordinates(serverPath, githubFullPath)
    }
    catch (e: Throwable) {
      return null
    }
  }

  private fun onSelectedUrlChanged() {
    val urlSelected = selectedUrl != null
    dialogStateListener.onOkActionEnabled(urlSelected)
    directoryField.isEnabled = urlSelected
    if (urlSelected) {
      val path = StringUtil.trimEnd(ClonePathProvider.relativeDirectoryPathForVcsUrl(project, selectedUrl!!), GitUtil.DOT_GIT)
      directoryField.trySetChildPath(path)
    }
  }

  /**
   * Since each repository can be in several states at the same time (shared access for a collaborator and shared access for org member) and
   * repositories for collaborators are loaded in separate request before repositories for org members, we need to update order of re-added
   * repo in order to place it close to other organization repos
   */
  private class UpdateOrderLinkedHashSet<T> : LinkedHashSet<T>() {
    override fun add(element: T): Boolean {
      val wasThere = remove(element)
      super.add(element)
      // Contract is "true if this set did not already contain the specified element"
      return !wasThere
    }
  }

  private fun resizeIcon(icon: Icon, size: Int): Icon {
    val scale = JBUI.scale(size).toFloat() / icon.iconWidth.toFloat()
    return IconUtil.scale(icon, null, scale)
  }

  private fun showPopupMenu() {
    val menuItems = mutableListOf<AccountMenuItem>()
    val project = ProjectManager.getInstance().defaultProject

    for ((index, account) in authenticationManager.getAccounts().withIndex()) {
      val user = userDetailsByAccount[account]

      val accountTitle = user?.login ?: account.name
      val serverInfo = account.server.toUrl().removePrefix("http://").removePrefix("https://")
      val avatar = avatarsByAccount[account] ?: defaultPopupAvatar
      val accountActions = mutableListOf<Action>()
      val showSeparatorAbove = index != 0

      if (user == null) {
        accountActions += Action("Log in\u2026", { switchToLogin(account) })
        accountActions += Action("Remove account", { authenticationManager.removeAccount(account) }, showSeparatorAbove = true)
      }
      else {
        if (account != authenticationManager.getDefaultAccount(project)) {
          accountActions += Action("Set as Default", { authenticationManager.setDefaultAccount(project, account) })
        }
        accountActions += Action("Open on GitHub", { BrowserUtil.browse(user.htmlUrl) }, AllIcons.Ide.External_link_arrow)
        accountActions += Action("Log Out\u2026", { authenticationManager.removeAccount(account) }, showSeparatorAbove = true)
      }

      menuItems += Account(accountTitle, serverInfo, avatar, accountActions, showSeparatorAbove)
    }
    menuItems += Action("Add Account\u2026", { switchToLogin() }, showSeparatorAbove = true)

    AccountsMenuListPopup(null, AccountMenuPopupStep(menuItems)).showUnderneathOf(accountsPanel)
  }
}
