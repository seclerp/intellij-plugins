package com.intellij.prettierjs;

import com.intellij.ProjectTopics;
import com.intellij.javascript.nodejs.util.NodePackage;
import com.intellij.lang.javascript.service.*;
import com.intellij.lang.javascript.service.protocol.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootEvent;
import com.intellij.openapi.roots.ModuleRootListener;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.psi.PsiFile;
import com.intellij.util.Consumer;
import com.intellij.util.ObjectUtils;
import com.intellij.util.concurrency.FixedFuture;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.webcore.util.JsonUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;
import java.util.concurrent.Future;

import static com.intellij.lang.javascript.service.JSLanguageServiceQueue.LOGGER;

public class PrettierLanguageServiceImpl extends JSLanguageServiceBase implements PrettierLanguageService {
  @Nullable
  private volatile SupportedFilesInfo mySupportedFiles;
  private volatile boolean myFlushConfigCache;

  public PrettierLanguageServiceImpl(@NotNull Project project) {
    super(project);
    project.getMessageBus().connect().subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
      @Override
      public void after(@NotNull List<? extends VFileEvent> events) {
        for (VFileEvent event : events) {
          if (!(event instanceof VFileContentChangeEvent) || PrettierUtil.isConfigFileOrPackageJson(event.getFile())) {
            myFlushConfigCache = true;
          }
        }
      }
    });

    project.getMessageBus().connect().subscribe(ProjectTopics.PROJECT_ROOTS, new ModuleRootListener() {
      @Override
      public void rootsChanged(ModuleRootEvent event) {
        myFlushConfigCache = true;
      }
    });
  }

  @NotNull
  @Override
  public Future<FormatResult> format(@NotNull PsiFile file, @NotNull NodePackage prettierPackage, @Nullable TextRange range) {
    String prettierPackagePath = JSLanguageServiceUtil.normalizeNameAndPath(prettierPackage.getSystemDependentPath());
    JSLanguageServiceQueue process = getProcess();
    if (process == null || !process.isValid()) {
      return new FixedFuture<>(FormatResult.error(PrettierBundle.message("service.not.started.message")));
    }
    ReformatFileCommand command = new ReformatFileCommand(file.getVirtualFile().getPath(), prettierPackagePath, file.getText(), range, myFlushConfigCache);
    return process.execute(command, (ignored, response) -> parseReformatResponse(response))
                  .whenComplete((f, g) -> myFlushConfigCache = false);
  }

  @Nullable
  @Override
  public SupportedFilesInfo getSupportedFiles() {
    return mySupportedFiles;
  }

  @Override
  @NotNull
  public Future<Void> initSupportedFiles(@NotNull NodePackage prettierPackage) {
    String prettierPackagePath = JSLanguageServiceUtil.normalizeNameAndPath(prettierPackage.getSystemDependentPath());
    JSLanguageServiceQueue process = getProcess();
    if (process == null || !process.isValid()) {
      return new FixedFuture<>(null);
    }
    return process.execute(new GetSupportedFilesCommand(prettierPackagePath), (ignored, response) -> parseGetSupportedFilesResponse(response))
                  .thenAccept((f) -> mySupportedFiles = f);
  }

  @NotNull
  private static FormatResult parseReformatResponse(JSLanguageServiceAnswer response) {
    final String error = JsonUtil.getChildAsString(response.getElement(), "error");
    if (!StringUtil.isEmpty(error)) {
      return FormatResult.error(error);
    }
    return FormatResult.formatted(JsonUtil.getChildAsString(response.getElement(), "formatted"));
  }

  @NotNull
  private static SupportedFilesInfo parseGetSupportedFilesResponse(@NotNull JSLanguageServiceAnswer response) {
    return new SupportedFilesInfo(
      ObjectUtils.coalesce(JsonUtil.getChildAsStringList(response.getElement(), "fileNames"), ContainerUtil.emptyList()),
      ObjectUtils.coalesce(JsonUtil.getChildAsStringList(response.getElement(), "extensions"), ContainerUtil.emptyList()));
  }

  @Nullable
  @Override
  protected JSLanguageServiceQueue createLanguageServiceQueue() {
    return new JSLanguageServiceQueueImpl(myProject, new Protocol(myProject, Consumer.EMPTY_CONSUMER),
                                          myProcessConnector, myDefaultReporter, new JSLanguageServiceDefaultCacheData());
  }

  @Override
  protected boolean needInitToolWindow() {
    return false;
  }

  private static class Protocol extends JSLanguageServiceNodeStdProtocolBase {
    public Protocol(@NotNull Project project, @NotNull Consumer<?> readyConsumer) {
      super(project, readyConsumer);
    }

    @Override
    protected JSLanguageServiceInitialState createState() {
      JSLanguageServiceInitialState state = new JSLanguageServiceInitialState();
      final File service = new File(JSLanguageServiceUtil.getPluginDirectory(this.getClass(), "prettierLanguageService"),
                                    "prettier-plugin-provider.js");
      if (!service.exists()) {
        LOGGER.error("prettier language service plugin not found");
      }
      state.pluginName = "prettier";
      state.pluginPath = service.getAbsolutePath();
      return state;
    }

    @Override
    public void dispose() {

    }

    @Nullable
    @Override
    protected String getNodeInterpreter() {
      return JSLanguageServiceUtil.getInterpreterPathIfValid(
        PrettierConfiguration.getInstance(myProject)
                             .getOrDetectInterpreterRef()
                             .resolve(myProject));
    }
  }

  private static class GetSupportedFilesCommand implements JSLanguageServiceObject, JSLanguageServiceSimpleCommand {
    public final String prettierPath;

    private GetSupportedFilesCommand(String path) {
      prettierPath = path;
    }

    @NotNull
    @Override
    public JSLanguageServiceObject toSerializableObject() {
      return this;
    }

    @NotNull
    @Override
    public String getCommand() {
      return "getSupportedFiles";
    }

    @Nullable
    @Override
    public String getPresentableText(@NotNull Project project) {
      return "getSupportedFiles";
    }
  }

  private static class ReformatFileCommand implements JSLanguageServiceObject, JSLanguageServiceSimpleCommand {
    public final String path;
    public final String prettierPath;
    public final String content;
    public Integer start;
    public Integer end;
    public final boolean flushConfigCache;

    public ReformatFileCommand(@NotNull String filePath,
                               @NotNull String prettierPath,
                               @NotNull String content,
                               @Nullable TextRange range, 
                               boolean flushConfigCache) {
      this.path = filePath;
      this.prettierPath = prettierPath;
      this.content = content;
      this.flushConfigCache = flushConfigCache;
      if (range != null) {
        start = range.getStartOffset();
        end = range.getEndOffset();
      }
    }

    @NotNull
    @Override
    public JSLanguageServiceObject toSerializableObject() {
      return this;
    }

    @NotNull
    @Override
    public String getCommand() {
      return "reformat";
    }

    @Nullable
    @Override
    public String getPresentableText(@NotNull Project project) {
      return "reformat";
    }
  }
}
