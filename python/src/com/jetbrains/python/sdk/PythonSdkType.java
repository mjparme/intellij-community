// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.jetbrains.python.sdk;

import com.google.common.collect.Lists;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.facet.Facet;
import com.intellij.facet.FacetConfiguration;
import com.intellij.facet.FacetManager;
import com.intellij.ide.DataManager;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationListener;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.impl.ApplicationInfoImpl;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.*;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileSystemUtil;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.*;
import com.intellij.psi.PsiElement;
import com.intellij.reference.SoftReference;
import com.intellij.remote.*;
import com.intellij.remote.ext.CredentialsCase;
import com.intellij.remote.ext.LanguageCaseCollector;
import com.intellij.util.Consumer;
import com.intellij.util.ExceptionUtil;
import com.intellij.util.ObjectUtils;
import com.intellij.util.containers.ContainerUtil;
import com.jetbrains.python.PyBundle;
import com.jetbrains.python.PyNames;
import com.jetbrains.python.PythonFileType;
import com.jetbrains.python.PythonHelper;
import com.jetbrains.python.codeInsight.typing.PyTypeShed;
import com.jetbrains.python.codeInsight.userSkeletons.PyUserSkeletonsUtil;
import com.jetbrains.python.facet.PythonFacetSettings;
import com.jetbrains.python.packaging.PyCondaPackageManagerImpl;
import com.jetbrains.python.psi.LanguageLevel;
import com.jetbrains.python.psi.impl.PyBuiltinCache;
import com.jetbrains.python.psi.search.PyProjectScopeBuilder;
import com.jetbrains.python.remote.PyCredentialsContribution;
import com.jetbrains.python.remote.PyRemoteSdkAdditionalDataBase;
import com.jetbrains.python.remote.PythonRemoteInterpreterManager;
import com.jetbrains.python.run.PyVirtualEnvReader;
import com.jetbrains.python.sdk.add.PyAddSdkDialog;
import com.jetbrains.python.sdk.flavors.CPythonSdkFlavor;
import com.jetbrains.python.sdk.flavors.PythonSdkFlavor;
import com.jetbrains.python.sdk.pipenv.PyPipEnvSdkAdditionalData;
import com.jetbrains.python.sdk.skeletons.PySkeletonRefresher;
import com.jetbrains.python.sdk.skeletons.PySkeletonRefresher.SkeletonHeader;
import com.jetbrains.python.sdk.skeletons.SkeletonVersionChecker;
import icons.PythonIcons;
import one.util.streamex.StreamEx;
import org.jdom.Element;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.jetbrains.python.psi.PyUtil.as;

/**
 * Class should be final and singleton since some code checks its instance by ref.
 *
 * @author yole
 */
public final class PythonSdkType extends SdkType {
  public static final String REMOTE_SOURCES_DIR_NAME = "remote_sources";
  private static final Logger LOG = Logger.getInstance(PythonSdkType.class);
  private static final String[] WINDOWS_EXECUTABLE_SUFFIXES = {"cmd", "exe", "bat", "com"};

  private static final int MINUTE = 60 * 1000; // 60 seconds, used with script timeouts
  @NonNls private static final String SKELETONS_TOPIC = "Skeletons";
  private static final String[] DIRS_WITH_BINARY = {"", "bin", "Scripts", "net45"};
  private static final String[] UNIX_BINARY_NAMES = {"jython", "pypy", "python", "python3"};
  private static final String[] WIN_BINARY_NAMES = {"jython.bat", "ipy.exe", "pypy.exe", "python.exe", "python3.exe"};

  private static final Key<WeakReference<Component>> SDK_CREATOR_COMPONENT_KEY = Key.create("#com.jetbrains.python.sdk.creatorComponent");
  private static final Predicate<Sdk> REMOTE_SDK_PREDICATE = PythonSdkType::isRemote;

  private static final Key<Map<String, String>> ENVIRONMENT_KEY = Key.create("ENVIRONMENT_KEY");

  public static PythonSdkType getInstance() {
    return SdkType.findInstance(PythonSdkType.class);
  }

  private PythonSdkType() {
    super("Python SDK");
  }

  @Override
  public Icon getIcon() {
    return PythonIcons.Python.Python;
  }

  @NotNull
  @Override
  public String getHelpTopic() {
    return "reference.project.structure.sdk.python";
  }

  @Override
  @NotNull
  public Icon getIconForAddAction() {
    return PythonFileType.INSTANCE.getIcon();
  }

  /**
   * Name of directory where skeleton files (despite the value) are stored.
   */
  public static final String SKELETON_DIR_NAME = "python_stubs";

  /**
   * @return name of builtins skeleton file; for Python 2.x it is '{@code __builtins__.py}'.
   */
  @NotNull
  @NonNls
  public static String getBuiltinsFileName(@NotNull Sdk sdk) {
    final LanguageLevel level = getLanguageLevelForSdk(sdk);
    return level.isPython2() ? PyBuiltinCache.BUILTIN_FILE : PyBuiltinCache.BUILTIN_FILE_3K;
  }

  @Override
  @NonNls
  @Nullable
  public String suggestHomePath() {
    final Sdk[] existingSdks = ProjectJdkTable.getInstance().getAllJdks();
    final List<PyDetectedSdk> sdks = PySdkExtKt.detectSystemWideSdks(null, Arrays.asList(existingSdks));
    final PyDetectedSdk latest = StreamEx.of(sdks).findFirst().orElse(null);
    if (latest != null) {
      return latest.getHomePath();
    }
    return null;
  }

  public static boolean hasValidSdk() {
    for (Sdk sdk : ProjectJdkTable.getInstance().getAllJdks()) {
      if (sdk.getSdkType() instanceof PythonSdkType) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean isValidSdkHome(@Nullable final String path) {
    return PythonSdkFlavor.getFlavor(path) != null;
  }

  public static boolean isInvalid(@NotNull Sdk sdk) {
    if (isRemote(sdk)) {
      return PyRemoteSdkValidator.Companion.isInvalid(sdk);
    }
    final VirtualFile interpreter = sdk.getHomeDirectory();
    return interpreter == null || !interpreter.exists();
  }

  public static boolean isRemote(@Nullable Sdk sdk) {
    return PySdkUtil.isRemote(sdk);
  }

  public static boolean isVagrant(@Nullable Sdk sdk) {
    if (sdk != null && sdk.getSdkAdditionalData() instanceof PyRemoteSdkAdditionalDataBase) {
      PyRemoteSdkAdditionalDataBase data = (PyRemoteSdkAdditionalDataBase)sdk.getSdkAdditionalData();

      return data.connectionCredentials().getRemoteConnectionType() == CredentialsType.VAGRANT;
    }
    return false;
  }

  public static boolean isRemote(@Nullable String sdkPath) {
    return isRemote(findSdkByPath(sdkPath));
  }

  @NotNull
  @Override
  public FileChooserDescriptor getHomeChooserDescriptor() {
    final boolean isWindows = SystemInfo.isWindows;
    return new FileChooserDescriptor(true, false, false, false, false, false) {
      @Override
      public void validateSelectedFiles(@NotNull VirtualFile[] files) throws Exception {
        if (files.length != 0) {
          if (!isValidSdkHome(files[0].getPath())) {
            throw new Exception(PyBundle.message("sdk.error.invalid.interpreter.name.$0", files[0].getName()));
          }
        }
      }

      @Override
      public boolean isFileVisible(VirtualFile file, boolean showHiddenFiles) {
        // TODO: add a better, customizable filtering
        if (!file.isDirectory()) {
          if (isWindows) {
            String path = file.getPath();
            boolean looksExecutable = false;
            for (String ext : WINDOWS_EXECUTABLE_SUFFIXES) {
              if (path.endsWith(ext)) {
                looksExecutable = true;
                break;
              }
            }
            return looksExecutable && super.isFileVisible(file, showHiddenFiles);
          }
        }
        return super.isFileVisible(file, showHiddenFiles);
      }
    }.withTitle(PyBundle.message("sdk.select.path")).withShowHiddenFiles(SystemInfo.isUnix);
  }

  @Override
  public boolean supportsCustomCreateUI() {
    return true;
  }

  @Override
  public void showCustomCreateUI(@NotNull SdkModel sdkModel,
                                 @NotNull final JComponent parentComponent,
                                 @NotNull final Consumer<Sdk> sdkCreatedCallback) {
    Project project = CommonDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext(parentComponent));
    PyAddSdkDialog.show(project, null, Arrays.asList(sdkModel.getSdks()), sdk -> {
        if (sdk != null) {
          sdk.putUserData(SDK_CREATOR_COMPONENT_KEY, new WeakReference<>(parentComponent));
          sdkCreatedCallback.consume(sdk);
        }
      });
  }

  public static boolean isVirtualEnv(@NotNull Sdk sdk) {
    final String path = sdk.getHomePath();
    return isVirtualEnv(path);
  }

  @Contract("null -> false")
  public static boolean isVirtualEnv(@Nullable String path) {
    return path != null && getVirtualEnvRoot(path) != null;
  }

  public static boolean isConda(@NotNull Sdk sdk) {
    return PyCondaPackageManagerImpl.isConda(sdk);
  }

  public static boolean isConda(@Nullable String sdkPath) {
    return PyCondaPackageManagerImpl.isConda(sdkPath);
  }

  public static boolean isCondaVirtualEnv(@NotNull Sdk sdk) {
    return PyCondaPackageManagerImpl.isCondaVirtualEnv(sdk);
  }

  @Nullable
  public Sdk getVirtualEnvBaseSdk(Sdk sdk) {
    if (isVirtualEnv(sdk)) {
      final PythonSdkFlavor flavor = PythonSdkFlavor.getFlavor(sdk);
      final String version = getVersionString(sdk);
      if (flavor != null && version != null) {
        for (Sdk baseSdk : getAllSdks()) {
          if (!isRemote(baseSdk)) {
            final PythonSdkFlavor baseFlavor = PythonSdkFlavor.getFlavor(baseSdk);
            if (!isVirtualEnv(baseSdk) && flavor.equals(baseFlavor) && version.equals(getVersionString(baseSdk))) {
              return baseSdk;
            }
          }
        }
      }
    }
    return null;
  }

  /**
   * @param binaryPath must point to a Python interpreter
   * @return if the surroundings look like a virtualenv installation, its root is returned (normally the grandparent of binaryPath).
   */
  @Nullable
  public static File getVirtualEnvRoot(@NotNull final String binaryPath) {
    final File bin = new File(binaryPath).getParentFile();
    if (bin != null) {
      final String rootPath = bin.getParent();
      if (rootPath != null) {
        final File root = new File(rootPath);
        final File activateThis = new File(bin, "activate_this.py");
        // binaryPath should contain an 'activate' script, and root should have bin (with us) and include and libp
        if (activateThis.exists()) {
          final File activate = findExecutableFile(bin, "activate");
          if (activate != null) {
            return root;
          }
        }
        // Python 3.3 virtualenvs can be found as described in PEP 405
        if (new File(root, "pyvenv.cfg").exists()) {
          return root;
        }
      }
    }
    return null;
  }

  /**
   * Finds a file that looks executable: an .exe or .cmd under windows, plain file under *nix.
   *
   * @param parent directory to look at
   * @param name   name of the executable without suffix
   * @return File representing the executable, or null.
   */
  @Nullable
  public static File findExecutableFile(File parent, String name) {
    if (SystemInfo.isWindows) {
      for (String suffix : WINDOWS_EXECUTABLE_SUFFIXES) {
        File file = new File(parent, name + "." + suffix);
        if (file.exists()) return file;
      }
    }
    else if (SystemInfo.isUnix) {
      File file = new File(parent, name);
      if (file.exists()) return file;
    }
    return null;
  }

  /**
   * Alters PATH so that a virtualenv is activated, if present.
   *
   * @param commandLine what to patch
   * @param sdk         SDK we're using
   */
  public static void patchCommandLineForVirtualenv(@NotNull GeneralCommandLine commandLine, @NotNull Sdk sdk) {
    final Map<String, String> virtualEnv = activateVirtualEnv(sdk);
    if (!virtualEnv.isEmpty()) {
      final Map<String, String> environment = commandLine.getEnvironment();

      for (Map.Entry<String, String> entry : virtualEnv.entrySet()) {
        final String key = entry.getKey();
        final String value = entry.getValue();

        if (environment.containsKey(key)) {
          if (key.equalsIgnoreCase(PySdkUtil.PATH_ENV_VARIABLE)) {
            PythonEnvUtil.addToPathEnvVar(environment.get(key), value, false);
          }
        }
        else {
          environment.put(key, value);
        }
      }
    }
  }

  @NotNull
  @Override
  public String suggestSdkName(@Nullable final String currentSdkName, final String sdkHome) {
    final String name = StringUtil.notNullize(suggestBaseSdkName(sdkHome), "Unknown");
    final File virtualEnvRoot = getVirtualEnvRoot(sdkHome);
    if (virtualEnvRoot != null) {
      final String path = FileUtil.getLocationRelativeToUserHome(virtualEnvRoot.getAbsolutePath());
      return name + " virtualenv at " + path;
    }
    else {
      return name;
    }
  }

  @Nullable
  public static String suggestBaseSdkName(@NotNull String sdkHome) {
    final PythonSdkFlavor flavor = PythonSdkFlavor.getFlavor(sdkHome);
    if (flavor == null) return null;
    return flavor.getName() + " " + flavor.getLanguageLevel(sdkHome);
  }

  @Override
  @Nullable
  public AdditionalDataConfigurable createAdditionalDataConfigurable(@NotNull final SdkModel sdkModel,
                                                                     @NotNull final SdkModificator sdkModificator) {
    return null;
  }

  @Override
  public void saveAdditionalData(@NotNull final SdkAdditionalData additionalData, @NotNull final Element additional) {
    if (additionalData instanceof PythonSdkAdditionalData) {
      ((PythonSdkAdditionalData)additionalData).save(additional);
    }
  }

  @Override
  public SdkAdditionalData loadAdditionalData(@NotNull final Sdk currentSdk, @NotNull final Element additional) {
    if (RemoteSdkCredentialsHolder.isRemoteSdk(currentSdk.getHomePath())) {
      PythonRemoteInterpreterManager manager = PythonRemoteInterpreterManager.getInstance();
      if (manager != null) {
        return manager.loadRemoteSdkData(currentSdk, additional);
      }
    }
    // TODO: Extract loading additional SDK data into a Python SDK provider
    final PyPipEnvSdkAdditionalData pipEnvData = PyPipEnvSdkAdditionalData.load(additional);
    if (pipEnvData != null) {
      return pipEnvData;
    }
    return PythonSdkAdditionalData.load(currentSdk, additional);
  }

  public static boolean isSkeletonsPath(String path) {
    return path.contains(SKELETON_DIR_NAME);
  }

  @Override
  @NotNull
  @NonNls
  public String getPresentableName() {
    return "Python SDK";
  }

  @NotNull
  @Override
  public String sdkPath(@NotNull VirtualFile homePath) {
    String path = super.sdkPath(homePath);
    PythonSdkFlavor flavor = PythonSdkFlavor.getFlavor(path);
    if (flavor != null) {
      VirtualFile sdkPath = flavor.getSdkPath(homePath);
      if (sdkPath != null) {
        return FileUtil.toSystemDependentName(sdkPath.getPath());
      }
    }
    return FileUtil.toSystemDependentName(path);
  }

  @Override
  public void setupSdkPaths(@NotNull Sdk sdk) {
    final Project project;
    final WeakReference<Component> ownerComponentRef = sdk.getUserData(SDK_CREATOR_COMPONENT_KEY);
    final Component ownerComponent = SoftReference.dereference(ownerComponentRef);
    if (ownerComponent != null) {
      project = CommonDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext(ownerComponent));
    }
    else {
      project = CommonDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext());
    }
    PythonSdkUpdater.updateOrShowError(sdk, null, project, ownerComponent);
  }

  @Override
  public boolean setupSdkPaths(@NotNull Sdk sdk, @NotNull SdkModel sdkModel) {
    return true;  // run setupSdkPaths only once (from PythonSdkDetailsStep). Skip this from showCustomCreateUI
  }

  public static void notifyRemoteSdkSkeletonsFail(final InvalidSdkException e, @Nullable final Runnable restartAction) {
    NotificationListener notificationListener;
    String notificationMessage;
    if (e.getCause() instanceof VagrantNotStartedException) {
      notificationListener =
        (notification, event) -> {
          final PythonRemoteInterpreterManager manager = PythonRemoteInterpreterManager.getInstance();
          if (manager != null) {
            try {
              VagrantNotStartedException cause = (VagrantNotStartedException)e.getCause();
              manager.runVagrant(cause.getVagrantFolder(), cause.getMachineName());
            }
            catch (ExecutionException e1) {
              throw new RuntimeException(e1);
            }
          }
          if (restartAction != null) {
            restartAction.run();
          }
        };
      notificationMessage = e.getMessage() + "\n<a href=\"#\">Launch vagrant and refresh skeletons</a>";
    }
    else if (ExceptionUtil.causedBy(e, ExceptionFix.class)) {
      final ExceptionFix fix = ExceptionUtil.findCause(e, ExceptionFix.class);
      notificationListener =
        (notification, event) -> {
          fix.apply();
          if (restartAction != null) {
            restartAction.run();
          }
        };
      notificationMessage = fix.getNotificationMessage(e.getMessage());
    }
    else {
      notificationListener = null;
      notificationMessage = e.getMessage();
    }

    Notifications.Bus.notify(
      new Notification(
        SKELETONS_TOPIC, "Couldn't refresh skeletons for remote interpreter",
        notificationMessage,
        NotificationType.WARNING,
        notificationListener
      )
    );
  }

  /**
   * In which root type built-in skeletons are put.
   */
  public static final OrderRootType BUILTIN_ROOT_TYPE = OrderRootType.CLASSES;

  @NotNull
  public static VirtualFile getSdkRootVirtualFile(@NotNull VirtualFile path) {
    String suffix = path.getExtension();
    if (suffix != null) {
      suffix = StringUtil.toLowerCase(suffix); // Why on earth empty suffix is null and not ""?
    }
    if (!path.isDirectory() && ("zip".equals(suffix) || "egg".equals(suffix))) {
      // a .zip / .egg file must have its root extracted first
      final VirtualFile jar = JarFileSystem.getInstance().getJarRootForLocalFile(path);
      if (jar != null) {
        return jar;
      }
    }
    return path;
  }

  /**
   * Returns skeletons location on the local machine. Independent of SDK credentials type (e.g. ssh, Vagrant, Docker or else).
   */
  public static String getSkeletonsPath(String basePath, String sdkHome) {
    String sep = File.separator;
    return getSkeletonsRootPath(basePath) + sep + FileUtil.toSystemIndependentName(sdkHome).hashCode() + sep;
  }

  public static String getSkeletonsRootPath(String basePath) {
    return basePath + File.separator + SKELETON_DIR_NAME;
  }

  @NotNull
  public static List<String> getSysPath(@NotNull Sdk sdk) throws InvalidSdkException {
    String working_dir = new File(sdk.getHomePath()).getParent();
    Application application = ApplicationManager.getApplication();
    if (application != null && (!application.isUnitTestMode() || ApplicationInfoImpl.isInStressTest())) {
      return getSysPathsFromScript(sdk);
    }
    else { // mock sdk
      List<String> ret = new ArrayList<>(1);
      ret.add(working_dir);
      return ret;
    }
  }

  @NotNull
  public static List<String> getSysPathsFromScript(@NotNull Sdk sdk) throws InvalidSdkException {
    // to handle the situation when PYTHONPATH contains ., we need to run the syspath script in the
    // directory of the script itself - otherwise the dir in which we run the script (e.g. /usr/bin) will be added to SDK path
    final String binaryPath = sdk.getHomePath();
    GeneralCommandLine cmd = PythonHelper.SYSPATH.newCommandLine(binaryPath, Lists.newArrayList());
    final ProcessOutput runResult = PySdkUtil.getProcessOutput(cmd, new File(binaryPath).getParent(),
                                                               activateVirtualEnv(sdk), MINUTE);
    if (!runResult.checkSuccess(LOG)) {
      throw new InvalidSdkException(String.format("Failed to determine Python's sys.path value:\nSTDOUT: %s\nSTDERR: %s",
                                                  runResult.getStdout(),
                                                  runResult.getStderr()));
    }
    return runResult.getStdoutLines();
  }

  @Nullable
  @Override
  public String getVersionString(@NotNull Sdk sdk) {
    if (isRemote(sdk)) {
      final PyRemoteSdkAdditionalDataBase data = (PyRemoteSdkAdditionalDataBase)sdk.getSdkAdditionalData();
      assert data != null;
      String versionString = data.getVersionString();
      if (StringUtil.isEmpty(versionString)) {
        final PythonRemoteInterpreterManager remoteInterpreterManager = PythonRemoteInterpreterManager.getInstance();
        if (remoteInterpreterManager != null) {
          try {
            versionString =
              remoteInterpreterManager.getInterpreterVersion(null, data);
          }
          catch (Exception e) {
            LOG.warn("Couldn't get interpreter version:" + e.getMessage(), e);
            versionString = "undefined";
          }
        }
        data.setVersionString(versionString);
      }
      return versionString;
    }
    else {
      return getVersionString(sdk.getHomePath());
    }
  }

  @Override
  @Nullable
  public String getVersionString(@Nullable final String sdkHome) {
    final PythonSdkFlavor flavor = PythonSdkFlavor.getFlavor(sdkHome);
    return flavor != null ? flavor.getVersionString(sdkHome) : null;
  }

  public static List<Sdk> getAllSdks() {
    return ProjectJdkTable.getInstance().getSdksOfType(getInstance());
  }

  @Nullable
  public static Sdk findPythonSdk(@Nullable Module module) {
    if (module == null) return null;
    final Sdk sdk = ModuleRootManager.getInstance(module).getSdk();
    if (sdk != null && sdk.getSdkType() instanceof PythonSdkType) return sdk;
    final Facet[] facets = FacetManager.getInstance(module).getAllFacets();
    for (Facet facet : facets) {
      final FacetConfiguration configuration = facet.getConfiguration();
      if (configuration instanceof PythonFacetSettings) {
        return ((PythonFacetSettings)configuration).getSdk();
      }
    }
    return null;
  }

  @Nullable
  public static Sdk findPythonSdk(@NotNull final PsiElement element) {
    return findPythonSdk(ModuleUtilCore.findModuleForPsiElement(element));
  }

  @Nullable
  public static Sdk findSdkByPath(@Nullable String path) {
    if (path != null) {
      return findSdkByPath(getAllSdks(), path);
    }
    return null;
  }

  @Nullable
  public static Sdk findSdkByPath(List<? extends Sdk> sdkList, @Nullable String path) {
    if (path != null) {
      for (Sdk sdk : sdkList) {
        if (sdk != null && FileUtil.pathsEqual(path, sdk.getHomePath())) {
          return sdk;
        }
      }
    }
    return null;
  }

  @NotNull
  public static LanguageLevel getLanguageLevelForSdk(@Nullable Sdk sdk) {
    if (sdk != null && sdk.getSdkType() instanceof PythonSdkType) {
      final PythonSdkFlavor flavor = PythonSdkFlavor.getFlavor(sdk);
      if (flavor != null) {
        return flavor.getLanguageLevel(sdk);
      }
    }
    return LanguageLevel.getDefault();
  }

  @Override
  public boolean isRootTypeApplicable(@NotNull final OrderRootType type) {
    return type == OrderRootType.CLASSES;
  }

  @Override
  public boolean sdkHasValidPath(@NotNull Sdk sdk) {
    if (PySdkUtil.isRemote(sdk)) {
      return true;
    }
    VirtualFile homeDir = sdk.getHomeDirectory();
    return homeDir != null && homeDir.isValid();
  }

  public static boolean isStdLib(@NotNull VirtualFile vFile, @Nullable Sdk pythonSdk) {
    if (pythonSdk != null) {
      @Nullable VirtualFile originFile = vFile;
      @NotNull String originPath = vFile.getPath();
      boolean checkOnRemoteFS = false;
      // All binary skeletons are collected under the same root regardless of their original location.
      // Because of that we need to use paths to the corresponding binary modules recorded in their headers.
      final SkeletonHeader header = readSkeletonHeader(originFile, pythonSdk);
      if (header != null) {
        // Binary module paths in skeleton headers of Mock SDK don't map to actual physical files.
        // Fallback to the old heuristic for these stubs.
        if (ApplicationManager.getApplication().isUnitTestMode() &&
            Objects.equals(vFile.getParent(), PySdkUtil.findSkeletonsDir(pythonSdk))) {
          return true;
        }

        final String binaryPath = header.getBinaryFile();
        // XXX Assume that all pre-generated stubs belong to the interpreter's stdlib -- might change in future with PY-32229
        if (binaryPath.equals(SkeletonVersionChecker.BUILTIN_NAME) || binaryPath.equals(SkeletonVersionChecker.PREGENERATED)) {
          return true;
        }
        if (isRemote(pythonSdk)) {
          checkOnRemoteFS = true;
          // Actual file is on remote file system and not available
          originFile = null;
        }
        else {
          originFile = VfsUtil.findFileByIoFile(new File(binaryPath), true);
        }
        originPath = binaryPath;
      }
      if (originFile != null) {
        originFile = ObjectUtils.notNull(originFile.getCanonicalFile(), originFile);
        originPath = originFile.getPath();
      }

      final VirtualFile libDir = PyProjectScopeBuilder.findLibDir(pythonSdk);
      if (libDir != null && isUnderLibDirButNotSitePackages(originFile, originPath, libDir, pythonSdk, checkOnRemoteFS)) {
        return true;
      }
      final VirtualFile venvLibDir = PyProjectScopeBuilder.findVirtualEnvLibDir(pythonSdk);
      if (venvLibDir != null && isUnderLibDirButNotSitePackages(originFile, originPath, venvLibDir, pythonSdk, checkOnRemoteFS)) {
        return true;
      }
      if (PyUserSkeletonsUtil.isStandardLibrarySkeleton(vFile)) {
        return true;
      }
      if (PyTypeShed.INSTANCE.isInStandardLibrary(vFile) && PyTypeShed.INSTANCE.isInside(vFile)) {
        return true;
      }
    }
    return false;
  }

  @Nullable
  private static SkeletonHeader readSkeletonHeader(@NotNull VirtualFile file, @NotNull Sdk pythonSdk) {
    final VirtualFile skeletonsDir = PySdkUtil.findSkeletonsDir(pythonSdk);
    if (skeletonsDir != null && VfsUtilCore.isAncestor(skeletonsDir, file, false)) {
      return PySkeletonRefresher.readSkeletonHeader(VfsUtilCore.virtualToIoFile(file));
    }
    return null;
  }

  @NotNull
  private static String mapToRemote(@NotNull String localRoot, @NotNull Sdk sdk) {
    final RemoteSdkAdditionalData remoteSdkData = as(sdk.getSdkAdditionalData(), RemoteSdkAdditionalData.class);
    if (remoteSdkData != null) {
      return remoteSdkData.getPathMappings().convertToRemote(localRoot);
    }
    return localRoot;
  }

  private static boolean isUnderLibDirButNotSitePackages(@Nullable VirtualFile file,
                                                         @NotNull String path,
                                                         @NotNull VirtualFile libDir,
                                                         @NotNull Sdk sdk,
                                                         boolean checkOnRemoteFS) {
    final VirtualFile originLibDir;
    final String originLibDirPath;
    if (checkOnRemoteFS) {
      originLibDir = libDir;
      originLibDirPath = mapToRemote(originLibDir.getPath(), sdk);
    }
    else {
      // Normalize the path to the lib directory on local FS
      originLibDir = ObjectUtils.notNull(libDir.getCanonicalFile(), libDir);
      originLibDirPath = originLibDir.getPath();
    }

    // This check is more brittle and thus used as a fallback measure
    if (checkOnRemoteFS || file == null) {
      final String normalizedLidDirPath = FileUtil.toSystemIndependentName(originLibDirPath);
      final String sitePackagesPath = normalizedLidDirPath + "/" + PyNames.SITE_PACKAGES;
      final String normalizedPath = FileUtil.toSystemIndependentName(path);
      return FileUtil.startsWith(normalizedPath, normalizedLidDirPath) && !FileUtil.startsWith(normalizedPath, sitePackagesPath);
    }
    else if (VfsUtilCore.isAncestor(originLibDir, file, false)) {
      final VirtualFile sitePackagesDir = originLibDir.findChild(PyNames.SITE_PACKAGES);
      return sitePackagesDir == null || !VfsUtilCore.isAncestor(sitePackagesDir, file, false);
    }
    return false;
  }

  /**
   * Returns the "site-packages" directory that is going to be used for installing new packages with {@code pip}.
   * <p>
   * Note that on a virtual env there might be two such directories in {@code sys.path} depending on whether
   * the option "--system-site-packages" was given during its creation. Then the one inside the actual virtual
   * env tree will be returned, as it's the one used to install new packages.
   * Also, on some systems, first of all in system distributions of Python on Linux, there might be no
   * "site-packages" at all, and this method returns {@code null} accordingly in this case.
   */
  @Nullable
  public static VirtualFile getSitePackagesDirectory(@NotNull Sdk pythonSdk) {
    final VirtualFile libDir;
    if (isVirtualEnv(pythonSdk)) {
      libDir = PyProjectScopeBuilder.findVirtualEnvLibDir(pythonSdk);
    }
    else {
      libDir = PyProjectScopeBuilder.findLibDir(pythonSdk);
    }
    return libDir != null ? libDir.findChild(PyNames.SITE_PACKAGES) : null;
  }

  @Nullable
  public static Sdk findPython2Sdk(@Nullable Module module) {
    final Sdk moduleSDK = findPythonSdk(module);
    if (moduleSDK != null && getLanguageLevelForSdk(moduleSDK).isPython2()) {
      return moduleSDK;
    }
    return findPython2Sdk(getAllSdks());
  }

  @Nullable
  public static Sdk findPython2Sdk(@NotNull List<? extends Sdk> sdks) {
    for (Sdk sdk : ContainerUtil.sorted(sdks, PreferredSdkComparator.INSTANCE)) {
      if (getLanguageLevelForSdk(sdk).isPython2()) {
        return sdk;
      }
    }
    return null;
  }

  @Nullable
  public static Sdk findLocalCPython(@Nullable Module module) {
    final Sdk moduleSDK = findPythonSdk(module);
    if (moduleSDK != null && !isRemote(moduleSDK) && PythonSdkFlavor.getFlavor(moduleSDK) instanceof CPythonSdkFlavor) {
      return moduleSDK;
    }
    for (Sdk sdk : ContainerUtil.sorted(getAllSdks(), PreferredSdkComparator.INSTANCE)) {
      if (!isRemote(sdk)) {
        return sdk;
      }
    }
    return null;
  }

  public static List<Sdk> getAllLocalCPythons() {
    return getAllSdks().stream().filter(REMOTE_SDK_PREDICATE.negate()).collect(Collectors.toList());
  }

  @Nullable
  public static String getPythonExecutable(@NotNull String rootPath) {
    final File rootFile = new File(rootPath);
    if (rootFile.isFile()) {
      return rootFile.getAbsolutePath();
    }
    for (String dir : DIRS_WITH_BINARY) {
      final File subDir;
      if (StringUtil.isEmpty(dir)) {
        subDir = rootFile;
      }
      else {
        subDir = new File(rootFile, dir);
      }
      if (!subDir.isDirectory()) {
        continue;
      }
      for (String binaryName : getBinaryNames()) {
        final File executable = new File(subDir, binaryName);
        if (executable.isFile()) {
          return executable.getAbsolutePath();
        }
      }
    }
    return null;
  }

  @Nullable
  public static String getExecutablePath(@NotNull final String homeDirectory, @NotNull String name) {
    File binPath = new File(homeDirectory);
    File binDir = binPath.getParentFile();
    if (binDir == null) return null;
    File runner = new File(binDir, name);
    if (runner.exists()) return LocalFileSystem.getInstance().extractPresentableUrl(runner.getPath());
    runner = new File(new File(binDir, "Scripts"), name);
    if (runner.exists()) return LocalFileSystem.getInstance().extractPresentableUrl(runner.getPath());
    runner = new File(new File(binDir.getParentFile(), "Scripts"), name);
    if (runner.exists()) return LocalFileSystem.getInstance().extractPresentableUrl(runner.getPath());
    runner = new File(new File(binDir.getParentFile(), "local"), name);
    if (runner.exists()) return LocalFileSystem.getInstance().extractPresentableUrl(runner.getPath());
    runner = new File(new File(new File(binDir.getParentFile(), "local"), "bin"), name);
    if (runner.exists()) return LocalFileSystem.getInstance().extractPresentableUrl(runner.getPath());

    // if interpreter is a symlink
    if (FileSystemUtil.isSymLink(homeDirectory)) {
      String resolvedPath = FileSystemUtil.resolveSymLink(homeDirectory);
      if (resolvedPath != null) {
        return getExecutablePath(resolvedPath, name);
      }
    }
    // Search in standard unix path
    runner = new File(new File("/usr", "bin"), name);
    if (runner.exists()) return LocalFileSystem.getInstance().extractPresentableUrl(runner.getPath());
    runner = new File(new File(new File("/usr", "local"), "bin"), name);
    if (runner.exists()) return LocalFileSystem.getInstance().extractPresentableUrl(runner.getPath());
    return null;
  }

  private static String[] getBinaryNames() {
    if (SystemInfo.isUnix) {
      return UNIX_BINARY_NAMES;
    }
    else {
      return WIN_BINARY_NAMES;
    }
  }

  public static boolean isIncompleteRemote(Sdk sdk) {
    if (PySdkUtil.isRemote(sdk)) {
      //noinspection ConstantConditions
      if (!((PyRemoteSdkAdditionalDataBase)sdk.getSdkAdditionalData()).isValid()) {
        return true;
      }
    }
    return false;
  }

  public static boolean isRunAsRootViaSudo(@NotNull Sdk sdk) {
    SdkAdditionalData data = sdk.getSdkAdditionalData();
    return data instanceof PyRemoteSdkAdditionalDataBase && ((PyRemoteSdkAdditionalDataBase)data).isRunAsRootViaSudo();
  }

  public static boolean hasInvalidRemoteCredentials(Sdk sdk) {
    if (PySdkUtil.isRemote(sdk)) {
      final Ref<Boolean> result = Ref.create(false);
      //noinspection ConstantConditions
      ((PyRemoteSdkAdditionalDataBase)sdk.getSdkAdditionalData()).switchOnConnectionType(
        new LanguageCaseCollector<PyCredentialsContribution>() {

          @Override
          protected void processLanguageContribution(PyCredentialsContribution languageContribution, Object credentials) {
            result.set(!languageContribution.isValid(credentials));
          }
        }.collectCases(
          PyCredentialsContribution.class,
          new CredentialsCase.Vagrant() {
            @Override
            public void process(VagrantBasedCredentialsHolder cred) {
              result.set(StringUtil.isEmpty(cred.getVagrantFolder()));
            }
          }
        ));
      return result.get();
    }
    return false;
  }

  @Deprecated
  @Nullable
  public static Sdk getSdk(@NotNull final PsiElement element) {
    return findPythonSdk(element);
  }

  @NotNull
  public static String getSdkKey(@NotNull Sdk sdk) {
    return sdk.getName();
  }

  @Nullable
  public static Sdk findSdkByKey(@NotNull String key) {
    return ProjectJdkTable.getInstance().findJdk(key);
  }

  @Override
  public boolean isLocalSdk(@NotNull Sdk sdk) {
    return !isRemote(sdk);
  }

  @NotNull
  public static Map<String, String> activateVirtualEnv(@NotNull Sdk sdk) {
    final Map<String, String> cached = sdk.getUserData(ENVIRONMENT_KEY);
    if (cached != null) return cached;

    final String sdkHome = sdk.getHomePath();
    if (sdkHome == null) return Collections.emptyMap();

    final Map<String, String> environment = activateVirtualEnv(sdkHome);
    sdk.putUserData(ENVIRONMENT_KEY, environment);
    return environment;
  }

  @NotNull
  public static Map<String, String> activateVirtualEnv(@NotNull String sdkHome) {
    PyVirtualEnvReader reader = new PyVirtualEnvReader(sdkHome);
    if (reader.getActivate() != null) {
      try {
        return Collections.unmodifiableMap(PyVirtualEnvReader.Companion.filterVirtualEnvVars(reader.readPythonEnv()));
      }
      catch (Exception e) {
        LOG.error("Couldn't read virtualenv variables", e);
      }
    }

    return Collections.emptyMap();
  }
}

