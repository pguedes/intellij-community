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
package org.jetbrains.jps.builders.java

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.containers.MultiMap
import org.jetbrains.jps.ModuleChunk
import org.jetbrains.jps.builders.DirtyFilesHolder
import org.jetbrains.jps.incremental.*
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Opcodes

import java.io.File
import java.util.*
import java.util.regex.Pattern
import gnu.trove.THashSet
import org.jetbrains.jps.builders.storage.StorageProvider
import org.jetbrains.jps.incremental.storage.AbstractStateStorage
import org.jetbrains.jps.incremental.storage.PathStringDescriptor
import com.intellij.util.io.EnumeratorStringDescriptor

/**
 * Mock builder which produces class file from several source files to test that our build infrastructure handle such cases properly.
 *
 * The builder processes *.p file, generates empty class for each such file and generates 'PackageFacade' class for each package
 * which references all classes from that package. Package name is derived from 'package <name>;' statement from a file or set to empty
 * if no such statement is found
 *
 * @author nik
 */
class MockPackageFacadeGenerator : ModuleLevelBuilder(BuilderCategory.SOURCE_PROCESSOR) {
  override fun build(context: CompileContext,
                     chunk: ModuleChunk,
                     dirtyFilesHolder: DirtyFilesHolder<JavaSourceRootDescriptor, ModuleBuildTarget>,
                     outputConsumer: ModuleLevelBuilder.OutputConsumer): ModuleLevelBuilder.ExitCode {
    val filesToCompile = MultiMap.createLinked<ModuleBuildTarget, File>()
    dirtyFilesHolder.processDirtyFiles { target, file, root ->
      if (isCompilable(file)) {
        filesToCompile.putValue(target, file)
      }
      true
    }

    val allFilesToCompile = ArrayList(filesToCompile.values())
    if (allFilesToCompile.isEmpty() && chunk.getTargets().all { dirtyFilesHolder.getRemovedFiles(it).all { !isCompilable(File(it)) } }) return ModuleLevelBuilder.ExitCode.NOTHING_DONE

    if (JavaBuilderUtil.isCompileJavaIncrementally(context)) {
      val logger = context.getLoggingManager().getProjectBuilderLogger()
      if (logger.isEnabled()) {
        if (!filesToCompile.isEmpty()) {
          logger.logCompiledFiles(allFilesToCompile, "MockPackageFacadeGenerator", "Compiling files:")
        }
      }
    }

    val mappings = context.getProjectDescriptor().dataManager.getMappings()
    val callback = JavaBuilderUtil.getDependenciesRegistrar(context)

    fun generateClass(packageName: String, className: String, target: ModuleBuildTarget, sources: Collection<String>, generate: (ClassWriter.() -> Unit)? = null) {
      val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES)
      val fullClassName = StringUtil.getQualifiedName(packageName, className).replace('.', '/')
      writer.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC, fullClassName, null, "java/lang/Object", null)
      if (generate != null) {
        writer.generate()
      }
      writer.visitEnd()
      val outputFile = File(target.getOutputDir(), "$fullClassName.class")
      val classBytes = writer.toByteArray()
      FileUtil.writeToFile(outputFile, classBytes)
      outputConsumer.registerOutputFile(target, outputFile, sources)
      callback.associate(fullClassName.replace('/', '.'), sources, ClassReader(classBytes))
    }

    for (target in chunk.getTargets()) {
      val packagesStorage = context.getProjectDescriptor().dataManager.getStorage(target, PACKAGE_CACHE_STORAGE_PROVIDER)
      for (file in filesToCompile[target]) {
        generateClass(getPackageName(file), FileUtil.getNameWithoutExtension(file), target, listOf(file.getAbsolutePath()))
      }

      val packagesToGenerate = filesToCompile[target].mapTo(THashSet<String>(), ::getPackageName)
      filesToCompile[target].mapNotNullTo(packagesToGenerate) { packagesStorage.getState(it.getAbsolutePath()) }
      val packagesFromDeletedFiles = dirtyFilesHolder.getRemovedFiles(target).filter { isCompilable(File(it)) }.mapNotNull { packagesStorage.getState(it) }
      packagesToGenerate.addAll(packagesFromDeletedFiles)

      val getParentFile: (File) -> File = { it.getParentFile() }
      val dirsToCheck = filesToCompile[target].mapTo(THashSet(FileUtil.FILE_HASHING_STRATEGY), getParentFile)
      packagesFromDeletedFiles.flatMap { mappings.getClassSources(mappings.getName(StringUtil.getQualifiedName(it, "PackageFacade"))) }
                              .map(getParentFile).filterNotNullTo(dirsToCheck)

      for (packageName in packagesToGenerate) {
        val files = dirsToCheck.map { it.listFiles() }.filterNotNull().flatMap { it.toList() }.filter { isCompilable(it) && packageName == getPackageName(it) }
        if (files.isEmpty()) continue

        val classNames = files.map { FileUtilRt.getNameWithoutExtension(it.getName()) }.sort()
        val sources = files.map { it.getAbsolutePath() }

        generateClass(packageName, "PackageFacade", target, sources) {
          for (fileName in classNames) {
            val fieldClass = StringUtil.getQualifiedName(packageName, fileName).replace('.', '/')
            visitField(Opcodes.ACC_PUBLIC, StringUtil.decapitalize(fileName), "L$fieldClass;", null, null).visitEnd()
          }
        }
        for (source in sources) {
          packagesStorage.update(FileUtil.toSystemIndependentName(source), packageName)
        }
      }
    }
    JavaBuilderUtil.registerFilesToCompile(context, allFilesToCompile)
    JavaBuilderUtil.registerSuccessfullyCompiled(context, allFilesToCompile)
    return ModuleLevelBuilder.ExitCode.OK
  }

  override fun getCompilableFileExtensions(): List<String> {
    return listOf("p")
  }

  override fun getPresentableName(): String {
    return "Mock Package Facade Generator"
  }
}

private val PACKAGE_CACHE_STORAGE_PROVIDER = object: StorageProvider<AbstractStateStorage<String, String>>() {
  override fun createStorage(targetDataDir: File): AbstractStateStorage<String, String> {
    val storageFile = File(targetDataDir, "mockPackageFacade/packages")
    return object : AbstractStateStorage<String, String>(storageFile, PathStringDescriptor(), EnumeratorStringDescriptor()) {
    }
  }
}

private fun getPackageName(sourceFile: File): String {
  val text = String(FileUtil.loadFileText(sourceFile))
  val matcher = Pattern.compile("\\p{javaWhitespace}*package\\p{javaWhitespace}+([^;]*);.*").matcher(text)
  if (matcher.matches()) {
    return matcher.group(1)
  }
  return ""
}

private fun isCompilable(file: File): Boolean {
  return FileUtilRt.extensionEquals(file.getName(), "p")
}
