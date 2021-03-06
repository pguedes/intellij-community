/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.plugins.groovy.dsl;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ex.ApplicationUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.impl.LoadTextUtil;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.*;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileAdapter;
import com.intellij.openapi.vfs.VirtualFileEvent;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.*;
import com.intellij.psi.impl.PsiModificationTrackerImpl;
import com.intellij.psi.scope.DelegatingScopeProcessor;
import com.intellij.psi.scope.PsiScopeProcessor;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.reference.SoftReference;
import com.intellij.util.ExceptionUtil;
import com.intellij.util.Function;
import com.intellij.util.PathUtil;
import com.intellij.util.containers.ConcurrentMultiMap;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.MultiMap;
import com.intellij.util.indexing.*;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.GroovyFileType;
import org.jetbrains.plugins.groovy.annotator.GroovyFrameworkConfigNotification;
import org.jetbrains.plugins.groovy.lang.psi.GroovyFile;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.blocks.GrClosableBlock;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.GrTypeDefinition;
import org.jetbrains.plugins.groovy.lang.psi.impl.statements.expressions.TypesUtil;
import org.jetbrains.plugins.groovy.lang.resolve.ResolveUtil;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * @author peter
 */
public class GroovyDslFileIndex extends ScalarIndexExtension<String> {
  private static final Key<Pair<GroovyDslExecutor, Long>> CACHED_EXECUTOR = Key.create("CachedGdslExecutor");
  private static final Logger LOG = Logger.getInstance(GroovyDslFileIndex.class);

  @NonNls public static final ID<String, Void> NAME = ID.create("GroovyDslFileIndex");
  @NonNls private static final String OUR_KEY = "ourKey";
  public static final String MODIFIED = "Modified";
  private final MyDataIndexer myDataIndexer = new MyDataIndexer();

  private static final MultiMap<String, LinkedBlockingQueue<Pair<VirtualFile, GroovyDslExecutor>>> filesInProcessing =
    new ConcurrentMultiMap<String, LinkedBlockingQueue<Pair<VirtualFile, GroovyDslExecutor>>>();

  private final EnumeratorStringDescriptor myKeyDescriptor = new EnumeratorStringDescriptor();

  public GroovyDslFileIndex() {
    VirtualFileManager.getInstance().addVirtualFileListener(new VirtualFileAdapter() {

      @Override
      public void contentsChanged(@NotNull VirtualFileEvent event) {
        if (event.getFileName().endsWith(".gdsl")) {
          disableFile(event.getFile(), MODIFIED);
        }
      }

    });
  }

  @Override
  @NotNull
  public ID<String, Void> getName() {
    return NAME;
  }

  @Override
  @NotNull
  public DataIndexer<String, Void, FileContent> getIndexer() {
    return myDataIndexer;
  }

  @NotNull
  @Override
  public KeyDescriptor<String> getKeyDescriptor() {
    return myKeyDescriptor;
  }

  @NotNull
  @Override
  public FileBasedIndex.InputFilter getInputFilter() {
    return new MyInputFilter();
  }

  @Override
  public boolean dependsOnFileContent() {
    return false;
  }

  @Override
  public int getVersion() {
    return 0;
  }

  @Nullable
  public static String getInactivityReason(VirtualFile file) {
    return DslActivationStatus.getInstance().getInactivityReason(file);
  }

  public static boolean isActivated(VirtualFile file) {
    return DslActivationStatus.getInstance().isActivated(file);
  }

  public static void activateUntilModification(final VirtualFile vfile) {
    DslActivationStatus.getInstance().activateUntilModification(vfile);
    clearScriptCache();
  }

  private static void clearScriptCache() {
    for (Project project : ProjectManager.getInstance().getOpenProjects()) {
      project.putUserData(SCRIPTS_CACHE, null);
      ((PsiModificationTrackerImpl)PsiManager.getInstance(project).getModificationTracker()).incCounter();
    }
  }

  static void disableFile(final VirtualFile vfile, String error) {
    DslActivationStatus.getInstance().disableFile(vfile, error);
    vfile.putUserData(CACHED_EXECUTOR, null);
    clearScriptCache();
  }


  @Nullable
  private static GroovyDslExecutor getCachedExecutor(@NotNull final VirtualFile file, final long stamp) {
    final Pair<GroovyDslExecutor, Long> pair = file.getUserData(CACHED_EXECUTOR);
    if (pair == null || pair.second.longValue() != stamp) {
      return null;
    }
    return pair.first;
  }

  @Nullable
  public static PsiClassType pocessScriptSuperClasses(@NotNull GroovyFile scriptFile) {
    if (!scriptFile.isScript()) return null;

    final VirtualFile virtualFile = scriptFile.getVirtualFile();
    if (virtualFile == null) return null;
    final String filePath = virtualFile.getPath();


    List<Trinity<String, String, GroovyDslScript>> supers = ContainerUtil.newArrayList();
    final Project project = scriptFile.getProject();
    for (GroovyDslScript script : getDslScripts(project)) {
      final MultiMap staticInfo = script.getStaticInfo();
      final Collection infos = staticInfo != null ? staticInfo.get("scriptSuperClass") : Collections.emptyList();

      for (Object info : infos) {
        if (info instanceof Map) {
          final Map map = (Map)info;

          final Object _pattern = map.get("pattern");
          final Object _superClass = map.get("superClass");

          if (_pattern instanceof String && _superClass instanceof String) {
            final String pattern = (String)_pattern;
            final String superClass = (String)_superClass;

            try {
              if (Pattern.matches(".*" + pattern, filePath)) {
                supers.add(Trinity.create(superClass, pattern, script));
              }
            }
            catch (RuntimeException e) {
              script.handleDslError(e);
            }
          }
        }
      }
    }

    if (!supers.isEmpty()) {
      final String className = supers.get(0).first;
      final GroovyDslScript script = supers.get(0).third;
      try {
        return TypesUtil.createTypeByFQClassName(className, scriptFile);
      }
      catch (ProcessCanceledException e) {
        throw e;
      }
      catch (RuntimeException e) {
        script.handleDslError(e);
        return null;
      }
    }
    /*else if (supers.size() > 1) {
      StringBuilder buffer = new StringBuilder("Several script super class patterns match file ").append(filePath).append(". <p> ");
      for (Trinity<String, String, GroovyDslScript> aSuper : supers) {
        buffer.append(aSuper.third.getFilePath()).append(" ").append(aSuper.second).append('\n');
      }
      NOTIFICATION_GROUP.createNotification("DSL script execution error", buffer.toString(), NotificationType.ERROR, null).notify(project);
      return null;
    }*/
    else {
      return null;
    }
  }

  public static boolean processExecutors(PsiType psiType, PsiElement place, final PsiScopeProcessor processor, ResolveState state) {
    if (insideAnnotation(place)) {
      // Basic filter, all DSL contexts are applicable for reference expressions only
      return true;
    }

    final String qname = psiType.getCanonicalText();

    final PsiFile placeFile = place.getContainingFile().getOriginalFile();

    final DelegatingScopeProcessor nameChecker = new DelegatingScopeProcessor(processor) {
      @Override
      public boolean execute(@NotNull PsiElement element, @NotNull ResolveState state) {
        if (element instanceof PsiMethod && ((PsiMethod)element).isConstructor()) {
          return processor.execute(element, state);
        }
        else if (element instanceof PsiNamedElement) {
          return ResolveUtil.processElement(processor, (PsiNamedElement)element, state);
        }
        else {
          return processor.execute(element, state);
        }
      }
    };

    for (GroovyDslScript script : getDslScripts(place.getProject())) {
      if (!script.processExecutor(nameChecker, psiType, place, placeFile, qname, state)) {
        return false;
      }
    }

    return true;
  }

  private static boolean insideAnnotation(@Nullable PsiElement place) {
    while (place != null) {
      if (place instanceof PsiAnnotation) return true;
      if (place instanceof GrClosableBlock ||
          place instanceof GrTypeDefinition ||
          place instanceof PsiFile) return false;
      place = place.getParent();
    }
    return false;
  }

  private static volatile SoftReference<List<Pair<File, GroovyDslExecutor>>> ourStandardScripts;

  @Nullable
  private static List<Pair<File, GroovyDslExecutor>> derefStandardScripts() {
    return SoftReference.dereference(ourStandardScripts);
  }

  @Nullable
  private static List<Pair<File, GroovyDslExecutor>> getStandardScripts() {
    List<Pair<File, GroovyDslExecutor>> result = derefStandardScripts();
    if (result != null) {
      return result;
    }

    final GroovyFrameworkConfigNotification[] extensions = GroovyFrameworkConfigNotification.EP_NAME.getExtensions();
    Callable<List<Pair<File, GroovyDslExecutor>>> action = new Callable<List<Pair<File, GroovyDslExecutor>>>() {
      @Override
      public List<Pair<File, GroovyDslExecutor>> call() throws Exception {
        if (GdslUtil.ourGdslStopped) {
          return null;
        }

        try {
          List<Pair<File, GroovyDslExecutor>> pairs = derefStandardScripts();
          if (pairs != null) {
            return pairs;
          }

          Set<Class> classes = new HashSet<Class>(ContainerUtil.map2Set(extensions, new Function<GroovyFrameworkConfigNotification, Class>() {
            @Override
            public Class fun(GroovyFrameworkConfigNotification notification) {
              return notification.getClass();
            }
          }));
          classes.add(GroovyFrameworkConfigNotification.class); // for default extension

          // perhaps a separate extension for that?
          Set<File> scriptFolders = new LinkedHashSet<File>();
          for (Class aClass : classes) {
            File jarPath = new File(PathUtil.getJarPathForClass(aClass));
            if (jarPath.isFile()) {
              jarPath = jarPath.getParentFile();
            }
            scriptFolders.add(new File(jarPath, "standardDsls"));
          }

          List<Pair<File, GroovyDslExecutor>> executors = new ArrayList<Pair<File, GroovyDslExecutor>>();
          for (File file : scriptFolders) {
            if (file.exists()) {
              File[] children = file.listFiles();
              if (children != null) {
                for (File child : children) {
                  final String fileName = child.getName();
                  if (fileName.endsWith(".gdsl")) {
                    try {
                      final String text = new String(FileUtil.loadFileText(child));
                      executors.add(Pair.create(child, new GroovyDslExecutor(text, fileName)));
                    }
                    catch (IOException e) {
                      LOG.error("Error while parsing gdsl file " + fileName, e);
                    }
                  }
                }
              }
            }
          }
          //noinspection AssignmentToStaticFieldFromInstanceMethod
          ourStandardScripts = new SoftReference<List<Pair<File, GroovyDslExecutor>>>(executors);
          return executors;
        }
        catch (Throwable e) {
          //noinspection InstanceofCatchParameter
          if (e instanceof Error) {
            GdslUtil.stopGdsl();
          }
          LOG.error(e);
          return null;
        }
      }
    };

    try {
      if (ApplicationManager.getApplication().isDispatchThread()) {
        return action.call();
      }
      return ApplicationUtil.runWithCheckCanceled(action, new EmptyProgressIndicator());
    }
    catch (Exception e) {
      ExceptionUtil.rethrowUnchecked(e);
      LOG.error(e);
      return null;
    }
  }

  private static final Key<CachedValue<List<GroovyDslScript>>> SCRIPTS_CACHE = Key.create("GdslScriptCache");

  private static List<GroovyDslScript> getDslScripts(final Project project) {
    return CachedValuesManager.getManager(project).getCachedValue(project, SCRIPTS_CACHE, new CachedValueProvider<List<GroovyDslScript>>() {
      @Override
      public Result<List<GroovyDslScript>> compute() {
        if (GdslUtil.ourGdslStopped) {
          return Result.create(Collections.<GroovyDslScript>emptyList(), ModificationTracker.NEVER_CHANGED);
        }

        // eagerly initialize some services used by background gdsl parsing threads
        // because service init requires a read action
        // and there could be a deadlock with a write action waiting already on EDT
        // if current thread is inside a non-cancellable read action
        GroovyDslExecutor.getIdeaVersion();
        DslActivationStatus.getInstance();

        int count = 0;

        List<GroovyDslScript> result = new ArrayList<GroovyDslScript>();

        List<Pair<File, GroovyDslExecutor>> standardScripts = getStandardScripts();
        if (standardScripts != null) {
          for (Pair<File, GroovyDslExecutor> pair : standardScripts) {
            result.add(new GroovyDslScript(project, null, pair.second, pair.first.getPath()));
          }
        }

        final LinkedBlockingQueue<Pair<VirtualFile, GroovyDslExecutor>> queue =
          new LinkedBlockingQueue<Pair<VirtualFile, GroovyDslExecutor>>();

        final ProjectFileIndex fileIndex = ProjectRootManager.getInstance(project).getFileIndex();
        final GlobalSearchScope scope = GlobalSearchScope.allScope(project);
        for (VirtualFile vfile : FileBasedIndex.getInstance().getContainingFiles(NAME, OUR_KEY, scope)) {
          if (!vfile.isValid()) {
            continue;
          }
          if (fileIndex.isInLibrarySource(vfile)) {
            continue;
          } 
          if (!fileIndex.isInLibraryClasses(vfile)) {
            if (!fileIndex.isInSourceContent(vfile) || !isActivated(vfile)) {
              continue;
            }
          }

          final long stamp = vfile.getModificationStamp();
          final GroovyDslExecutor cached = getCachedExecutor(vfile, stamp);
          if (cached == null) {
            scheduleParsing(queue, project, vfile, stamp, LoadTextUtil.loadText(vfile).toString());
            count++;
          }
          else {
            result.add(new GroovyDslScript(project, vfile, cached, vfile.getPath()));
          }
        }

        try {
          while (count > 0 && !GdslUtil.ourGdslStopped) {
            ProgressManager.checkCanceled();
            final Pair<VirtualFile, GroovyDslExecutor> pair = queue.poll(20, TimeUnit.MILLISECONDS);
            if (pair != null) {
              count--;
              if (pair.second != null) {
                result.add(new GroovyDslScript(project, pair.first, pair.second, pair.first.getPath()));
              }
            }
          }
        }
        catch (InterruptedException e) {
          LOG.error(e);
        }

        return Result.create(result, PsiModificationTracker.MODIFICATION_COUNT, ProjectRootManager.getInstance(project));
      }
    }, false);
  }

  private static class MyDataIndexer implements DataIndexer<String, Void, FileContent> {

    @Override
    @NotNull
    public Map<String, Void> map(@NotNull final FileContent inputData) {
      return Collections.singletonMap(OUR_KEY, null);
    }
  }

  private static class MyInputFilter extends DefaultFileTypeSpecificInputFilter {
    MyInputFilter() {
      super(GroovyFileType.GROOVY_FILE_TYPE);
    }

    @Override
    public boolean acceptInput(@NotNull final VirtualFile file) {
      return StringUtil.endsWith(file.getNameSequence(), ".gdsl");
    }
  }

  private static void scheduleParsing(final LinkedBlockingQueue<Pair<VirtualFile, GroovyDslExecutor>> queue,
                                      final Project project,
                                      final VirtualFile vfile,
                                      final long stamp,
                                      final String text) {
    final String fileUrl = vfile.getUrl();

    final Runnable parseScript = new Runnable() {
      @Override
      public void run() {
        GroovyDslExecutor executor = getCachedExecutor(vfile, stamp);
        try {
          if (executor == null && isActivated(vfile)) {
            executor = createExecutor(text, vfile, project);
            // executor is not only time-consuming to create, but also takes some PermGenSpace
            // => we can't afford garbage-collecting it together with PsiFile
            // => cache globally by file instance
            vfile.putUserData(CACHED_EXECUTOR, Pair.create(executor, stamp));
            if (executor != null) {
              activateUntilModification(vfile);
            }
          }
        }
        finally {
          // access to our MultiMap should be synchronized
          synchronized (filesInProcessing) {
            // put evaluated executor to all queues
            for (LinkedBlockingQueue<Pair<VirtualFile, GroovyDslExecutor>> queue : filesInProcessing.remove(fileUrl)) {
              queue.offer(Pair.create(vfile, executor));
            }
          }
        }
      }
    };

    //noinspection SynchronizationOnLocalVariableOrMethodParameter
    synchronized (filesInProcessing) { //ensure that only one thread calculates dsl executor
      final boolean isNewRequest = !filesInProcessing.containsKey(fileUrl);
      filesInProcessing.putValue(fileUrl, queue);
      if (isNewRequest) {
        ApplicationManager.getApplication().executeOnPooledThread(parseScript);
      }
    }
  }

  @Nullable
  private static GroovyDslExecutor createExecutor(String text, VirtualFile vfile, final Project project) {
    if (GdslUtil.ourGdslStopped) {
      return null;
    }

    try {
      return new GroovyDslExecutor(text, vfile.getName());
    }
    catch (final Throwable e) {
      if (project.isDisposed()) {
        LOG.error(e);
        return null;
      }

      if (ApplicationManager.getApplication().isUnitTestMode()) {
        LOG.error(e);
        return null;
      }
      invokeDslErrorPopup(e, project, vfile);

      //noinspection InstanceofCatchParameter
      if (e instanceof OutOfMemoryError) {
        GdslUtil.stopGdsl();
        throw (Error)e;
      }
      //noinspection InstanceofCatchParameter
      if (e instanceof NoClassDefFoundError) {
        GdslUtil.stopGdsl();
        throw (NoClassDefFoundError) e;
      }

      return null;
    }
  }
  static void invokeDslErrorPopup(Throwable e, final Project project, @NotNull VirtualFile vfile) {
    DslErrorReporter.getInstance().invokeDslErrorPopup(e, project, vfile);
  }

}
