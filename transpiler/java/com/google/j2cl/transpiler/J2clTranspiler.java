/*
 * Copyright 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.j2cl.transpiler;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.j2cl.ast.CompilationUnit;
import com.google.j2cl.ast.visitors.ArrayAccessNormalizer;
import com.google.j2cl.ast.visitors.BridgeMethodsCreator;
import com.google.j2cl.ast.visitors.ControlStatementFormatter;
import com.google.j2cl.ast.visitors.CreateImplicitConstructors;
import com.google.j2cl.ast.visitors.DevirtualizeBoxedTypesAndJsFunctionImplementations;
import com.google.j2cl.ast.visitors.DevirtualizeMethodCalls;
import com.google.j2cl.ast.visitors.EnumMethodsCreator;
import com.google.j2cl.ast.visitors.ExpandCompoundAssignments;
import com.google.j2cl.ast.visitors.FilloutMissingSourceMapInformation;
import com.google.j2cl.ast.visitors.FixSuperCallQualifiers;
import com.google.j2cl.ast.visitors.ImplementAssertStatements;
import com.google.j2cl.ast.visitors.ImplementInstanceInitialization;
import com.google.j2cl.ast.visitors.ImplementInstanceOfs;
import com.google.j2cl.ast.visitors.ImplementLambdaExpressions;
import com.google.j2cl.ast.visitors.ImplementStaticInitialization;
import com.google.j2cl.ast.visitors.ImplementSynchronizedStatements;
import com.google.j2cl.ast.visitors.InsertBitwiseOperatorBooleanCoercions;
import com.google.j2cl.ast.visitors.InsertBoxingConversions;
import com.google.j2cl.ast.visitors.InsertCastOnNewInstances;
import com.google.j2cl.ast.visitors.InsertCastsToTypeBounds;
import com.google.j2cl.ast.visitors.InsertErasureTypeSafetyCasts;
import com.google.j2cl.ast.visitors.InsertExceptionConversions;
import com.google.j2cl.ast.visitors.InsertExplicitSuperCalls;
import com.google.j2cl.ast.visitors.InsertIntegerCoercions;
import com.google.j2cl.ast.visitors.InsertJsEnumBoxingAndUnboxingConversions;
import com.google.j2cl.ast.visitors.InsertNarrowingPrimitiveConversions;
import com.google.j2cl.ast.visitors.InsertNarrowingReferenceConversions;
import com.google.j2cl.ast.visitors.InsertStringConversions;
import com.google.j2cl.ast.visitors.InsertTypeAnnotationOnGenericReturnTypes;
import com.google.j2cl.ast.visitors.InsertUnboxingConversions;
import com.google.j2cl.ast.visitors.InsertWideningPrimitiveConversions;
import com.google.j2cl.ast.visitors.JsInteropRestrictionsChecker;
import com.google.j2cl.ast.visitors.MoveVariableDeclarationsToEnclosingBlock;
import com.google.j2cl.ast.visitors.NormalizationPass;
import com.google.j2cl.ast.visitors.NormalizeArrayCreations;
import com.google.j2cl.ast.visitors.NormalizeArrayLiterals;
import com.google.j2cl.ast.visitors.NormalizeCasts;
import com.google.j2cl.ast.visitors.NormalizeCatchClauses;
import com.google.j2cl.ast.visitors.NormalizeConstructors;
import com.google.j2cl.ast.visitors.NormalizeEnumClasses;
import com.google.j2cl.ast.visitors.NormalizeEquality;
import com.google.j2cl.ast.visitors.NormalizeFieldInitialization;
import com.google.j2cl.ast.visitors.NormalizeFunctionExpressions;
import com.google.j2cl.ast.visitors.NormalizeInstanceOfs;
import com.google.j2cl.ast.visitors.NormalizeInterfaceMethods;
import com.google.j2cl.ast.visitors.NormalizeJsAwaitMethodInvocations;
import com.google.j2cl.ast.visitors.NormalizeJsDocCastExpressions;
import com.google.j2cl.ast.visitors.NormalizeJsEnums;
import com.google.j2cl.ast.visitors.NormalizeJsFunctionPropertyInvocations;
import com.google.j2cl.ast.visitors.NormalizeJsVarargs;
import com.google.j2cl.ast.visitors.NormalizeLiterals;
import com.google.j2cl.ast.visitors.NormalizeLongs;
import com.google.j2cl.ast.visitors.NormalizeMultiExpressions;
import com.google.j2cl.ast.visitors.NormalizeNestedClassConstructors;
import com.google.j2cl.ast.visitors.NormalizeOverlayMembers;
import com.google.j2cl.ast.visitors.NormalizeStaticMemberQualifiers;
import com.google.j2cl.ast.visitors.NormalizeStaticNativeMemberReferences;
import com.google.j2cl.ast.visitors.NormalizeSwitchStatements;
import com.google.j2cl.ast.visitors.NormalizeTryWithResources;
import com.google.j2cl.ast.visitors.NormalizeTypeLiterals;
import com.google.j2cl.ast.visitors.OptimizeAnonymousInnerClassesToFunctionExpressions;
import com.google.j2cl.ast.visitors.OptimizeAutoValue;
import com.google.j2cl.ast.visitors.RemoveNoopStatements;
import com.google.j2cl.ast.visitors.RemoveUnneededJsDocCasts;
import com.google.j2cl.ast.visitors.RewriteStringEquals;
import com.google.j2cl.ast.visitors.VerifyNormalizedUnits;
import com.google.j2cl.ast.visitors.VerifyParamAndArgCounts;
import com.google.j2cl.ast.visitors.VerifySingleAstReference;
import com.google.j2cl.ast.visitors.VerifyVariableScoping;
import com.google.j2cl.common.Problems;
import com.google.j2cl.common.Problems.FatalError;
import com.google.j2cl.generator.OutputGeneratorStage;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Translation tool for generating JavaScript source files from Java sources. */
class J2clTranspiler {

  /** Runs the entire J2CL pipeline. */
  static Problems transpile(J2clTranspilerOptions options) {
    // Compiler has no static state, but rather uses thread local variables.
    // Because of this, we invoke the compiler on a different thread each time.
    ExecutorService executorService = Executors.newSingleThreadExecutor();
    Future<Problems> result =
        executorService.submit(() -> new J2clTranspiler(options).transpileImpl());
    // Shutdown the executor service since it will only run a single transpilation. If not shutdown
    // it prevents the JVM from ending the process (see Executors.newFixedThreadPool()). This is not
    // normally observed since the transpiler in normal circumstances ends with System.exit() which
    // ends all threads. But when the transpilation throws an exception, the exception propagates
    // out of main() and the process lingers due the live threads from these executors.
    executorService.shutdown();

    try {
      return Uninterruptibles.getUninterruptibly(result);
    } catch (ExecutionException e) {
      // Try unwrapping the cause...
      Throwables.throwIfUnchecked(e.getCause());
      throw new AssertionError(e.getCause());
    }
  }

  private final Problems problems = new Problems();
  private final J2clTranspilerOptions options;

  private J2clTranspiler(J2clTranspilerOptions options) {
    this.options = options;
  }

  private Problems transpileImpl() {
    try {
      List<CompilationUnit> j2clUnits =
          options
              .getFrontend()
              .getCompilationUnits(
                  options.getClasspaths(),
                  options.getSources(),
                  options.getGenerateKytheIndexingMetadata(),
                  problems);
      if (!j2clUnits.isEmpty()) {
        checkUnits(j2clUnits);
        normalizeUnits(j2clUnits);
      }
      generateOutputs(j2clUnits);
      return problems;
    } catch (Problems.Exit e) {
      return e.getProblems();
    } finally {
      maybeCloseFileSystem();
    }
  }

  private void checkUnits(List<CompilationUnit> j2clUnits) {
    JsInteropRestrictionsChecker.check(j2clUnits, problems);
    problems.abortIfHasErrors();
  }

  private void normalizeUnits(List<CompilationUnit> j2clUnits) {
    for (CompilationUnit j2clUnit : j2clUnits) {
      for (NormalizationPass pass : getPasses()) {
        pass.execute(j2clUnit);
      }
    }
  }

  private ImmutableList<NormalizationPass> getPasses() {
    // TODO(b/117155139): Review the ordering of passes.
    return ImmutableList.of(
        // Pre-verifications
        new VerifySingleAstReference(),
        new VerifyParamAndArgCounts(),
        new VerifyVariableScoping(),

        // Class structure normalizations.
        new OptimizeAutoValue(options.getExperimentalOptimizeAutovalue()),
        new ImplementLambdaExpressions(),
        new OptimizeAnonymousInnerClassesToFunctionExpressions(),
        new NormalizeFunctionExpressions(),
        // Default constructors and explicit super calls should be synthesized first.
        new CreateImplicitConstructors(),
        new InsertExplicitSuperCalls(),
        new BridgeMethodsCreator(),
        new EnumMethodsCreator(),
        // TODO(b/31865368): Remove RewriteStringEquals pass once delayed field initialization
        //  is introduced and String.java gets updated to use it.
        new RewriteStringEquals(),
        new DevirtualizeBoxedTypesAndJsFunctionImplementations(),
        new NormalizeTryWithResources(),
        new NormalizeCatchClauses(),
        // Runs before normalizing nested classes.
        new InsertCastOnNewInstances(),
        // Must run before Enum normalization
        new FixSuperCallQualifiers(),

        // Runs after all passes that synthesize overlays.
        new NormalizeEnumClasses(),
        new NormalizeJsEnums(),
        new NormalizeOverlayMembers(),
        new NormalizeInterfaceMethods(),
        // End of class structure normalization.

        // Statement/Expression normalizations
        new NormalizeArrayLiterals(),
        new NormalizeStaticMemberQualifiers(),
        // Runs after NormalizeStaticMemberQualifiersPass.
        new DevirtualizeMethodCalls(),
        new ControlStatementFormatter(),
        new NormalizeMultiExpressions(),
        // Runs after NormalizeMultiExpressions to make sure it only sees valid l-values.
        new ExpandCompoundAssignments(),
        new InsertErasureTypeSafetyCasts(),
        // Runs before unboxing conversion.
        new InsertStringConversions(),
        new InsertNarrowingReferenceConversions(),
        new InsertUnboxingConversions(),
        new InsertBoxingConversions(),
        new InsertNarrowingPrimitiveConversions(),
        new InsertWideningPrimitiveConversions(),
        new NormalizeLongs(),
        new InsertIntegerCoercions(),
        new InsertBitwiseOperatorBooleanCoercions(),
        new NormalizeJsFunctionPropertyInvocations(),
        // Run before other passes that normalize JsEnum expressions, but after all the normal
        // Java semantic conversions.
        new InsertJsEnumBoxingAndUnboxingConversions(),
        new NormalizeSwitchStatements(),
        new ArrayAccessNormalizer(),
        new ImplementAssertStatements(),
        new ImplementSynchronizedStatements(),
        new NormalizeFieldInitialization(),
        new ImplementInstanceInitialization(),
        new NormalizeNestedClassConstructors(),
        new NormalizeConstructors(),
        new NormalizeTypeLiterals(),
        new NormalizeCasts(),
        new NormalizeInstanceOfs(),
        new NormalizeEquality(),
        new NormalizeStaticNativeMemberReferences(),
        new NormalizeJsVarargs(),
        new NormalizeArrayCreations(),
        new InsertExceptionConversions(),
        new NormalizeLiterals(),

        // Needs to run after passes that do code synthesis are run so that it handles the
        // synthesize code as well.
        // TODO(b/35241823): Revisit this pass if jscompiler adds a way to express constraints
        // to template variables.
        new InsertCastsToTypeBounds(),

        // TODO(b/72652198): remove the temporary fix once switch to JSCompiler's new type
        // checker.
        new InsertTypeAnnotationOnGenericReturnTypes(),

        // Perform post cleanups.
        new ImplementStaticInitialization(),
        // Needs to run after ImplementStaticInitialization since ImplementIsInstanceMethods creates
        // static methods which should not call $clinit.
        new ImplementInstanceOfs(),
        // Normalize multiexpressions again to remove unnecessary clutter, but run before
        // variable motion.
        new NormalizeMultiExpressions(),
        new MoveVariableDeclarationsToEnclosingBlock(),
        // Remove redundant JsDocCasts.
        new RemoveUnneededJsDocCasts(),
        new NormalizeJsDocCastExpressions(),

        // Handle await keyword.
        new NormalizeJsAwaitMethodInvocations(),
        new RemoveNoopStatements(),

        // Enrich source mapping information for better stack deobfuscation.
        new FilloutMissingSourceMapInformation(),

        // Post-verifications
        new VerifySingleAstReference(),
        new VerifyParamAndArgCounts(),
        new VerifyVariableScoping(),
        new VerifyNormalizedUnits());
  }

  private void generateOutputs(List<CompilationUnit> j2clCompilationUnits) {
    new OutputGeneratorStage(
            options.getNativeSources(),
            options.getOutput(),
            options.getLibraryInfoOutput(),
            options.getEmitReadableLibraryInfo(),
            options.getEmitReadableSourceMap(),
            options.getGenerateKytheIndexingMetadata(),
            problems)
        .generateOutputs(j2clCompilationUnits);
  }

  private void maybeCloseFileSystem() {
    FileSystem outputFileSystem = options.getOutput().getFileSystem();
    if (outputFileSystem.getClass().getCanonicalName().equals("com.sun.nio.zipfs.ZipFileSystem")
        || outputFileSystem.getClass().getCanonicalName().equals("jdk.nio.zipfs.ZipFileSystem")) {
      try {
        outputFileSystem.close();
      } catch (IOException e) {
        problems.fatal(FatalError.CANNOT_CLOSE_ZIP, e.getMessage());
      }
    }
  }
}
