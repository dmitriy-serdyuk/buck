/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.jvm.java;

import static com.facebook.buck.jvm.java.JavaLibraryRules.getAbiRulesWherePossible;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.common.ResourceValidator;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.RichStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;

public class DefaultJavaLibraryBuilder {
  protected final BuildTarget libraryTarget;
  protected final BuildTarget initialBuildTarget;
  protected final ProjectFilesystem projectFilesystem;
  protected final BuildRuleParams initialParams;
  @Nullable private final JavaBuckConfig javaBuckConfig;
  protected final TargetGraph targetGraph;
  protected final BuildRuleResolver buildRuleResolver;
  protected final SourcePathResolver sourcePathResolver;
  protected final CellPathResolver cellRoots;
  protected final SourcePathRuleFinder ruleFinder;
  protected ImmutableSortedSet<SourcePath> srcs = ImmutableSortedSet.of();
  protected ImmutableSortedSet<SourcePath> resources = ImmutableSortedSet.of();
  protected Optional<SourcePath> proguardConfig = Optional.empty();
  protected ImmutableList<String> postprocessClassesCommands = ImmutableList.of();
  protected ImmutableSortedSet<BuildRule> fullJarExportedDeps = ImmutableSortedSet.of();
  protected ImmutableSortedSet<BuildRule> fullJarProvidedDeps = ImmutableSortedSet.of();
  protected boolean trackClassUsage = false;
  protected boolean compileAgainstAbis = false;
  protected Optional<Path> resourcesRoot = Optional.empty();
  protected Optional<SourcePath> unbundledResourcesRoot = Optional.empty();
  protected Optional<SourcePath> manifestFile = Optional.empty();
  protected Optional<String> mavenCoords = Optional.empty();
  protected ImmutableSortedSet<BuildTarget> tests = ImmutableSortedSet.of();
  protected RemoveClassesPatternsMatcher classesToRemoveFromJar =
      RemoveClassesPatternsMatcher.EMPTY;
  protected ExtraClasspathFromContextFunction extraClasspathFromContextFunction =
      ExtraClasspathFromContextFunction.EMPTY;
  protected boolean sourceAbisAllowed = true;
  @Nullable protected JavacOptions initialJavacOptions = null;
  @Nullable private JavaLibraryDescription.CoreArg args = null;
  @Nullable private ConfiguredCompiler configuredCompiler;

  protected DefaultJavaLibraryBuilder(
      TargetGraph targetGraph,
      BuildTarget initialBuildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams initialParams,
      BuildRuleResolver buildRuleResolver,
      CellPathResolver cellRoots,
      JavaBuckConfig javaBuckConfig) {
    libraryTarget =
        HasJavaAbi.isLibraryTarget(initialBuildTarget)
            ? initialBuildTarget
            : HasJavaAbi.getLibraryTarget(initialBuildTarget);

    this.targetGraph = targetGraph;
    this.initialBuildTarget = initialBuildTarget;
    this.projectFilesystem = projectFilesystem;
    this.initialParams = initialParams;
    this.buildRuleResolver = buildRuleResolver;
    this.cellRoots = cellRoots;
    this.javaBuckConfig = javaBuckConfig;

    ruleFinder = new SourcePathRuleFinder(buildRuleResolver);
    sourcePathResolver = DefaultSourcePathResolver.from(ruleFinder);
    setCompileAgainstAbis(javaBuckConfig.shouldCompileAgainstAbis());
  }

  protected DefaultJavaLibraryBuilder(
      TargetGraph targetGraph,
      BuildTarget initialBuildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams initialParams,
      BuildRuleResolver buildRuleResolver,
      CellPathResolver cellRoots) {
    libraryTarget =
        HasJavaAbi.isLibraryTarget(initialBuildTarget)
            ? initialBuildTarget
            : HasJavaAbi.getLibraryTarget(initialBuildTarget);

    this.targetGraph = targetGraph;
    this.initialBuildTarget = initialBuildTarget;
    this.projectFilesystem = projectFilesystem;
    this.initialParams = initialParams;
    this.buildRuleResolver = buildRuleResolver;
    this.cellRoots = cellRoots;

    ruleFinder = new SourcePathRuleFinder(buildRuleResolver);
    sourcePathResolver = DefaultSourcePathResolver.from(ruleFinder);
    javaBuckConfig = null;
  }

  public DefaultJavaLibraryBuilder setArgs(JavaLibraryDescription.CoreArg args) {
    this.args = args;
    return setSrcs(args.getSrcs())
        .setResources(args.getResources())
        .setResourcesRoot(args.getResourcesRoot())
        .setUnbundledResourcesRoot(args.getUnbundledResourcesRoot())
        .setProguardConfig(args.getProguardConfig())
        .setPostprocessClassesCommands(args.getPostprocessClassesCommands())
        .setExportedDeps(args.getExportedDeps())
        .setProvidedDeps(args.getProvidedDeps())
        .setTests(args.getTests())
        .setManifestFile(args.getManifestFile())
        .setMavenCoords(args.getMavenCoords())
        .setSourceAbisAllowed(args.getGenerateAbiFromSource().orElse(sourceAbisAllowed))
        .setClassesToRemoveFromJar(new RemoveClassesPatternsMatcher(args.getRemoveClasses()));
  }

  public DefaultJavaLibraryBuilder setJavacOptions(JavacOptions javacOptions) {
    this.initialJavacOptions = javacOptions;
    return this;
  }

  public DefaultJavaLibraryBuilder setExtraClasspathFromContextFunction(
      ExtraClasspathFromContextFunction extraClasspathFromContextFunction) {
    this.extraClasspathFromContextFunction = extraClasspathFromContextFunction;
    return this;
  }

  public DefaultJavaLibraryBuilder setSourceAbisAllowed(boolean sourceAbisAllowed) {
    this.sourceAbisAllowed = sourceAbisAllowed;
    return this;
  }

  public DefaultJavaLibraryBuilder setSrcs(ImmutableSortedSet<SourcePath> srcs) {
    this.srcs = srcs;
    return this;
  }

  public DefaultJavaLibraryBuilder setResources(ImmutableSortedSet<SourcePath> resources) {
    this.resources =
        ResourceValidator.validateResources(sourcePathResolver, projectFilesystem, resources);
    return this;
  }

  public DefaultJavaLibraryBuilder setProguardConfig(Optional<SourcePath> proguardConfig) {
    this.proguardConfig = proguardConfig;
    return this;
  }

  public DefaultJavaLibraryBuilder setPostprocessClassesCommands(
      ImmutableList<String> postprocessClassesCommands) {
    this.postprocessClassesCommands = postprocessClassesCommands;
    return this;
  }

  public DefaultJavaLibraryBuilder setExportedDeps(ImmutableSortedSet<BuildTarget> exportedDeps) {
    this.fullJarExportedDeps = buildRuleResolver.getAllRules(exportedDeps);
    return this;
  }

  @VisibleForTesting
  public DefaultJavaLibraryBuilder setExportedDepRules(ImmutableSortedSet<BuildRule> exportedDeps) {
    this.fullJarExportedDeps = exportedDeps;
    return this;
  }

  public DefaultJavaLibraryBuilder setProvidedDeps(ImmutableSortedSet<BuildTarget> providedDeps) {
    this.fullJarProvidedDeps = buildRuleResolver.getAllRules(providedDeps);
    return this;
  }

  public DefaultJavaLibraryBuilder setTrackClassUsage(boolean trackClassUsage) {
    this.trackClassUsage = trackClassUsage;
    return this;
  }

  public DefaultJavaLibraryBuilder setResourcesRoot(Optional<Path> resourcesRoot) {
    this.resourcesRoot = resourcesRoot;
    return this;
  }

  public DefaultJavaLibraryBuilder setUnbundledResourcesRoot(
      Optional<SourcePath> unbundledResourcesRoot) {
    this.unbundledResourcesRoot = unbundledResourcesRoot;
    return this;
  }

  public DefaultJavaLibraryBuilder setManifestFile(Optional<SourcePath> manifestFile) {
    this.manifestFile = manifestFile;
    return this;
  }

  public DefaultJavaLibraryBuilder setMavenCoords(Optional<String> mavenCoords) {
    this.mavenCoords = mavenCoords;
    return this;
  }

  public DefaultJavaLibraryBuilder setTests(ImmutableSortedSet<BuildTarget> tests) {
    this.tests = tests;
    return this;
  }

  public DefaultJavaLibraryBuilder setClassesToRemoveFromJar(
      RemoveClassesPatternsMatcher classesToRemoveFromJar) {
    this.classesToRemoveFromJar = classesToRemoveFromJar;
    return this;
  }

  public DefaultJavaLibraryBuilder setConfiguredCompiler(
      @Nullable ConfiguredCompiler configuredCompiler) {
    this.configuredCompiler = configuredCompiler;
    return this;
  }

  protected DefaultJavaLibraryBuilder setCompileAgainstAbis(boolean compileAgainstAbis) {
    this.compileAgainstAbis = compileAgainstAbis;
    return this;
  }

  public final DefaultJavaLibrary build() {
    BuilderHelper helper = newHelper();
    return helper.build();
  }

  public final BuildRule buildAbi() {
    return newHelper().buildAbi();
  }

  protected BuilderHelper newHelper() {
    return new BuilderHelper();
  }

  protected class BuilderHelper {
    @Nullable private DefaultJavaLibrary libraryRule;
    @Nullable private CalculateAbiFromSource sourceAbiRule;
    @Nullable private BuildRuleParams finalParams;
    @Nullable private ImmutableSortedSet<BuildRule> finalFullJarDeclaredDeps;
    @Nullable private ImmutableSortedSet<BuildRule> compileTimeClasspathUnfilteredFullDeps;
    @Nullable private ImmutableSortedSet<BuildRule> compileTimeClasspathFullDeps;
    @Nullable private ImmutableSortedSet<BuildRule> compileTimeClasspathAbiDeps;
    @Nullable private ZipArchiveDependencySupplier abiClasspath;
    @Nullable private JarBuildStepsFactory jarBuildStepsFactory;
    @Nullable private BuildTarget abiJar;
    @Nullable private JavacOptions javacOptions;

    protected DefaultJavaLibrary build() {
      return getLibraryRule(false);
    }

    protected BuildRule buildAbi() {
      if (HasJavaAbi.isClassAbiTarget(initialBuildTarget)) {
        return buildAbiFromClasses();
      } else if (HasJavaAbi.isSourceAbiTarget(initialBuildTarget)) {
        CalculateAbiFromSource abiRule = getSourceAbiRule(false);
        getLibraryRule(true);
        return abiRule;
      } else if (HasJavaAbi.isVerifiedSourceAbiTarget(initialBuildTarget)) {
        BuildRule classAbi =
            buildRuleResolver.requireRule(HasJavaAbi.getClassAbiJar(libraryTarget));
        BuildRule sourceAbi =
            buildRuleResolver.requireRule(HasJavaAbi.getSourceAbiJar(libraryTarget));

        return new CompareAbis(
            initialBuildTarget,
            projectFilesystem,
            initialParams
                .withDeclaredDeps(ImmutableSortedSet.of(classAbi, sourceAbi))
                .withoutExtraDeps(),
            sourcePathResolver,
            classAbi.getSourcePathToOutput(),
            sourceAbi.getSourcePathToOutput(),
            javaBuckConfig.getSourceAbiVerificationMode());
      }

      throw new AssertionError(
          String.format(
              "%s is not an ABI target but went down the ABI codepath", initialBuildTarget));
    }

    @Nullable
    protected BuildTarget getAbiJar() {
      if (!willProduceOutputJar()) {
        return null;
      }

      if (abiJar == null) {
        if (shouldBuildAbiFromSource()) {
          JavaBuckConfig.SourceAbiVerificationMode sourceAbiVerificationMode =
              javaBuckConfig.getSourceAbiVerificationMode();
          abiJar =
              sourceAbiVerificationMode == JavaBuckConfig.SourceAbiVerificationMode.OFF
                  ? HasJavaAbi.getSourceAbiJar(libraryTarget)
                  : HasJavaAbi.getVerifiedSourceAbiJar(libraryTarget);
        } else {
          abiJar = HasJavaAbi.getClassAbiJar(libraryTarget);
        }
      }

      return abiJar;
    }

    private boolean willProduceOutputJar() {
      return !srcs.isEmpty() || !resources.isEmpty() || manifestFile.isPresent();
    }

    private boolean willProduceSourceAbi() {
      return willProduceOutputJar() && shouldBuildAbiFromSource();
    }

    private boolean shouldBuildAbiFromSource() {
      return isCompilingJava()
          && !srcs.isEmpty()
          && sourceAbisEnabled()
          && sourceAbisAllowed
          && postprocessClassesCommands.isEmpty();
    }

    private boolean isCompilingJava() {
      return getConfiguredCompiler() instanceof JavacToJarStepFactory;
    }

    private boolean sourceAbisEnabled() {
      return javaBuckConfig != null && javaBuckConfig.shouldGenerateAbisFromSource();
    }

    private DefaultJavaLibrary getLibraryRule(boolean addToIndex) {
      if (libraryRule == null) {
        BuildRuleParams finalParams = getFinalParams();
        CalculateAbiFromSource sourceAbiRule = null;
        if (willProduceSourceAbi()) {
          sourceAbiRule = getSourceAbiRule(true);
          finalParams = finalParams.copyAppendingExtraDeps(sourceAbiRule);
        }

        libraryRule =
            new DefaultJavaLibrary(
                initialBuildTarget,
                projectFilesystem,
                finalParams,
                sourcePathResolver,
                getJarBuildStepsFactory(),
                proguardConfig,
                getFinalFullJarDeclaredDeps(),
                fullJarExportedDeps,
                fullJarProvidedDeps,
                getAbiJar(),
                mavenCoords,
                tests,
                getRequiredForSourceAbi());

        if (sourceAbiRule != null) {
          libraryRule.setSourceAbi(sourceAbiRule);
        }

        if (addToIndex) {
          buildRuleResolver.addToIndex(libraryRule);
        }
      }

      return libraryRule;
    }

    protected final boolean getRequiredForSourceAbi() {
      return args != null && args.getRequiredForSourceAbi();
    }

    private CalculateAbiFromSource getSourceAbiRule(boolean addToIndex) {
      BuildTarget abiTarget = HasJavaAbi.getSourceAbiJar(libraryTarget);
      if (sourceAbiRule == null) {
        sourceAbiRule =
            new CalculateAbiFromSource(
                abiTarget,
                projectFilesystem,
                getFinalParams(),
                ruleFinder,
                getJarBuildStepsFactory());
        if (addToIndex) {
          buildRuleResolver.addToIndex(sourceAbiRule);
        }
      }
      return sourceAbiRule;
    }

    private BuildRule buildAbiFromClasses() {
      BuildTarget abiTarget = HasJavaAbi.getClassAbiJar(libraryTarget);
      BuildRule libraryRule = buildRuleResolver.requireRule(libraryTarget);

      return CalculateAbiFromClasses.of(
          abiTarget,
          ruleFinder,
          projectFilesystem,
          initialParams,
          Preconditions.checkNotNull(libraryRule.getSourcePathToOutput()),
          javaBuckConfig != null
              && javaBuckConfig.getSourceAbiVerificationMode()
                  != JavaBuckConfig.SourceAbiVerificationMode.OFF);
    }

    protected final BuildRuleParams getFinalParams() {
      if (finalParams == null) {
        finalParams = buildFinalParams();
      }

      return finalParams;
    }

    protected final ImmutableSortedSet<BuildRule> getFinalFullJarDeclaredDeps() {
      if (finalFullJarDeclaredDeps == null) {
        finalFullJarDeclaredDeps = buildFinalFullJarDeclaredDeps();
      }

      return finalFullJarDeclaredDeps;
    }

    protected ImmutableSortedSet<BuildRule> buildFinalFullJarDeclaredDeps() {
      return ImmutableSortedSet.copyOf(
          Iterables.concat(
              initialParams.getDeclaredDeps().get(),
              getConfiguredCompiler().getDeclaredDeps(ruleFinder)));
    }

    protected final ImmutableSortedSet<SourcePath> getFinalCompileTimeClasspathSourcePaths() {
      ImmutableSortedSet<BuildRule> buildRules =
          compileAgainstAbis ? getCompileTimeClasspathAbiDeps() : getCompileTimeClasspathFullDeps();

      return buildRules
          .stream()
          .map(BuildRule::getSourcePathToOutput)
          .filter(Objects::nonNull)
          .collect(MoreCollectors.toImmutableSortedSet());
    }

    protected final ImmutableSortedSet<BuildRule> getCompileTimeClasspathFullDeps() {
      if (compileTimeClasspathFullDeps == null) {
        compileTimeClasspathFullDeps =
            getCompileTimeClasspathUnfilteredFullDeps()
                .stream()
                .filter(dep -> dep instanceof HasJavaAbi)
                .collect(MoreCollectors.toImmutableSortedSet());
      }

      return compileTimeClasspathFullDeps;
    }

    protected final ImmutableSortedSet<BuildRule> getCompileTimeClasspathAbiDeps() {
      if (compileTimeClasspathAbiDeps == null) {
        compileTimeClasspathAbiDeps = buildCompileTimeClasspathAbiDeps();
      }

      return compileTimeClasspathAbiDeps;
    }

    protected final ZipArchiveDependencySupplier getAbiClasspath() {
      if (abiClasspath == null) {
        abiClasspath = buildAbiClasspath();
      }

      return abiClasspath;
    }

    protected final ConfiguredCompiler getConfiguredCompiler() {
      if (configuredCompiler == null) {
        configuredCompiler = buildConfiguredCompiler();
      }

      return configuredCompiler;
    }

    protected BuildRuleParams buildFinalParams() {
      ImmutableSortedSet<BuildRule> compileTimeClasspathAbiDeps = getCompileTimeClasspathAbiDeps();
      ImmutableSortedSet.Builder<BuildRule> declaredDepsBuilder = ImmutableSortedSet.naturalOrder();
      ImmutableSortedSet.Builder<BuildRule> extraDepsBuilder = ImmutableSortedSet.naturalOrder();
      if (compileAgainstAbis) {
        declaredDepsBuilder.addAll(
            getAbiRulesWherePossible(buildRuleResolver, getFinalFullJarDeclaredDeps()));
        // We remove provided and exported deps since we'll be adding the ABI rules of these and
        // don't want to end up with both full & ABI rules
        extraDepsBuilder.addAll(
            Sets.difference(
                initialParams.getExtraDeps().get(),
                Sets.union(fullJarProvidedDeps, fullJarExportedDeps)));
      } else {
        declaredDepsBuilder.addAll(getFinalFullJarDeclaredDeps());
        extraDepsBuilder
            .addAll(initialParams.getExtraDeps().get())
            .addAll(
                Sets.difference(
                    getCompileTimeClasspathUnfilteredFullDeps(), initialParams.getBuildDeps()));
      }
      ImmutableSortedSet<BuildRule> declaredDeps = declaredDepsBuilder.build();

      // The extra deps contain rules that may not come from the deps-related arguments of the
      // target, but are required for building this rule. Some default extra deps may be provided
      // and exported rules, annotation processor related rules, gen_aidl rules, gen rules, and zip
      // rules. The compile time classpath deps and deps from the compile step factory are manually
      // added as these are required for building this rule.
      // Extra deps remain separate from the declared deps because there are places where the
      // declared deps are grabbed and are expected to reflect the actual deps argument of the
      // target. In addition, when compiling against ABIs, extra deps shouldn't be translated to
      // their ABI rules as their full JARs are required (with exception of classpath rules).
      ImmutableSortedSet<BuildRule> extraDeps =
          extraDepsBuilder
              .addAll(Sets.difference(compileTimeClasspathAbiDeps, declaredDeps))
              .addAll(getConfiguredCompiler().getExtraDeps(ruleFinder))
              .build();

      return initialParams.withDeclaredDeps(declaredDeps).withExtraDeps(extraDeps);
    }

    protected final ImmutableSortedSet<BuildRule> getCompileTimeClasspathUnfilteredFullDeps() {
      if (compileTimeClasspathUnfilteredFullDeps == null) {
        Iterable<BuildRule> firstOrderDeps =
            Iterables.concat(
                getFinalFullJarDeclaredDeps(), fullJarExportedDeps, fullJarProvidedDeps);

        ImmutableSortedSet<BuildRule> rulesExportedByDependencies =
            BuildRules.getExportedRules(firstOrderDeps);

        compileTimeClasspathUnfilteredFullDeps =
            RichStream.from(Iterables.concat(firstOrderDeps, rulesExportedByDependencies))
                .collect(MoreCollectors.toImmutableSortedSet());
      }

      return compileTimeClasspathUnfilteredFullDeps;
    }

    protected ImmutableSortedSet<BuildRule> buildCompileTimeClasspathAbiDeps() {
      return JavaLibraryRules.getAbiRules(buildRuleResolver, getCompileTimeClasspathFullDeps());
    }

    protected ZipArchiveDependencySupplier buildAbiClasspath() {
      return new ZipArchiveDependencySupplier(
          ruleFinder,
          getCompileTimeClasspathAbiDeps()
              .stream()
              .map(BuildRule::getSourcePathToOutput)
              .collect(MoreCollectors.toImmutableSortedSet()));
    }

    protected ConfiguredCompiler buildConfiguredCompiler() {
      return new JavacToJarStepFactory(
          getJavac(), getJavacOptions(), extraClasspathFromContextFunction);
    }

    protected final JarBuildStepsFactory getJarBuildStepsFactory() {
      if (jarBuildStepsFactory == null) {
        jarBuildStepsFactory = buildJarBuildStepsFactory();
      }
      return jarBuildStepsFactory;
    }

    protected JarBuildStepsFactory buildJarBuildStepsFactory() {
      return new JarBuildStepsFactory(
          projectFilesystem,
          ruleFinder,
          getConfiguredCompiler(),
          srcs,
          resources,
          resourcesRoot,
          manifestFile,
          postprocessClassesCommands,
          getAbiClasspath(),
          trackClassUsage,
          getFinalCompileTimeClasspathSourcePaths(),
          classesToRemoveFromJar,
          getRequiredForSourceAbi());
    }

    protected final JavacOptions getJavacOptions() {
      if (javacOptions == null) {
        javacOptions = buildJavacOptions();
      }
      return javacOptions;
    }

    protected JavacOptions buildJavacOptions() {
      return Preconditions.checkNotNull(initialJavacOptions);
    }
  }

  protected Javac getJavac() {
    return JavacFactory.create(ruleFinder, Preconditions.checkNotNull(javaBuckConfig), args);
  }
}
