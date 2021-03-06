/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.apple;

import com.facebook.buck.cxx.CxxCompilationDatabase;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxStrip;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.LinkerMapMode;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkables;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Either;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.FlavorDomainException;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.HasContacts;
import com.facebook.buck.rules.HasTestTimeout;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.MetadataProvidingDescription;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.swift.SwiftLibraryDescription;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.Optionals;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.facebook.buck.versions.Version;
import com.facebook.buck.zip.UnzipStep;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.immutables.value.Value;

public class AppleTestDescription
    implements Description<AppleTestDescriptionArg>,
        Flavored,
        ImplicitDepsInferringDescription<AppleTestDescription.AbstractAppleTestDescriptionArg>,
        MetadataProvidingDescription<AppleTestDescriptionArg> {

  /** Flavors for the additional generated build rules. */
  static final Flavor LIBRARY_FLAVOR = InternalFlavor.of("apple-test-library");

  static final Flavor BUNDLE_FLAVOR = InternalFlavor.of("apple-test-bundle");
  private static final Flavor UNZIP_XCTOOL_FLAVOR = InternalFlavor.of("unzip-xctool");

  private static final ImmutableSet<Flavor> SUPPORTED_FLAVORS =
      ImmutableSet.of(LIBRARY_FLAVOR, BUNDLE_FLAVOR);

  /**
   * Auxiliary build modes which makes this description emit just the results of the underlying
   * library delegate.
   */
  private static final Set<Flavor> AUXILIARY_LIBRARY_FLAVORS =
      ImmutableSet.of(
          CxxCompilationDatabase.COMPILATION_DATABASE,
          CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR,
          CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR,
          CxxDescriptionEnhancer.SANDBOX_TREE_FLAVOR);

  private final AppleConfig appleConfig;
  private final AppleLibraryDescription appleLibraryDescription;
  private final FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain;
  private final FlavorDomain<AppleCxxPlatform> appleCxxPlatformFlavorDomain;
  private final Flavor defaultCxxFlavor;
  private final CodeSignIdentityStore codeSignIdentityStore;
  private final ProvisioningProfileStore provisioningProfileStore;
  private final Supplier<Optional<Path>> xcodeDeveloperDirectorySupplier;
  private final Optional<Long> defaultTestRuleTimeoutMs;

  public AppleTestDescription(
      AppleConfig appleConfig,
      AppleLibraryDescription appleLibraryDescription,
      FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain,
      FlavorDomain<AppleCxxPlatform> appleCxxPlatformFlavorDomain,
      Flavor defaultCxxFlavor,
      CodeSignIdentityStore codeSignIdentityStore,
      ProvisioningProfileStore provisioningProfileStore,
      Supplier<Optional<Path>> xcodeDeveloperDirectorySupplier,
      Optional<Long> defaultTestRuleTimeoutMs) {
    this.appleConfig = appleConfig;
    this.appleLibraryDescription = appleLibraryDescription;
    this.cxxPlatformFlavorDomain = cxxPlatformFlavorDomain;
    this.appleCxxPlatformFlavorDomain = appleCxxPlatformFlavorDomain;
    this.defaultCxxFlavor = defaultCxxFlavor;
    this.codeSignIdentityStore = codeSignIdentityStore;
    this.provisioningProfileStore = provisioningProfileStore;
    this.xcodeDeveloperDirectorySupplier = xcodeDeveloperDirectorySupplier;
    this.defaultTestRuleTimeoutMs = defaultTestRuleTimeoutMs;
  }

  @Override
  public Class<AppleTestDescriptionArg> getConstructorArgType() {
    return AppleTestDescriptionArg.class;
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains() {
    return appleLibraryDescription.flavorDomains();
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return Sets.difference(flavors, SUPPORTED_FLAVORS).isEmpty()
        || appleLibraryDescription.hasFlavors(flavors);
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      AppleTestDescriptionArg args) {
    AppleDebugFormat debugFormat =
        AppleDebugFormat.FLAVOR_DOMAIN
            .getValue(buildTarget)
            .orElse(appleConfig.getDefaultDebugInfoFormatForTests());
    if (buildTarget.getFlavors().contains(debugFormat.getFlavor())) {
      buildTarget = buildTarget.withoutFlavors(debugFormat.getFlavor());
    }

    boolean createBundle =
        Sets.intersection(buildTarget.getFlavors(), AUXILIARY_LIBRARY_FLAVORS).isEmpty();
    // Flavors pertaining to the library targets that are generated.
    Sets.SetView<Flavor> libraryFlavors =
        Sets.difference(buildTarget.getFlavors(), AUXILIARY_LIBRARY_FLAVORS);
    boolean addDefaultPlatform = libraryFlavors.isEmpty();
    ImmutableSet.Builder<Flavor> extraFlavorsBuilder = ImmutableSet.builder();
    if (createBundle) {
      extraFlavorsBuilder.add(LIBRARY_FLAVOR, CxxDescriptionEnhancer.MACH_O_BUNDLE_FLAVOR);
    }
    extraFlavorsBuilder.add(debugFormat.getFlavor());
    if (addDefaultPlatform) {
      extraFlavorsBuilder.add(defaultCxxFlavor);
    }

    Optional<MultiarchFileInfo> multiarchFileInfo =
        MultiarchFileInfos.create(appleCxxPlatformFlavorDomain, buildTarget);
    AppleCxxPlatform appleCxxPlatform;
    ImmutableList<CxxPlatform> cxxPlatforms;
    if (multiarchFileInfo.isPresent()) {
      ImmutableList.Builder<CxxPlatform> cxxPlatformBuilder = ImmutableList.builder();
      for (BuildTarget thinTarget : multiarchFileInfo.get().getThinTargets()) {
        cxxPlatformBuilder.add(cxxPlatformFlavorDomain.getValue(thinTarget).get());
      }
      cxxPlatforms = cxxPlatformBuilder.build();
      appleCxxPlatform = multiarchFileInfo.get().getRepresentativePlatform();
    } else {
      CxxPlatform cxxPlatform =
          cxxPlatformFlavorDomain
              .getValue(buildTarget)
              .orElse(cxxPlatformFlavorDomain.getValue(defaultCxxFlavor));
      cxxPlatforms = ImmutableList.of(cxxPlatform);
      try {
        appleCxxPlatform = appleCxxPlatformFlavorDomain.getValue(cxxPlatform.getFlavor());
      } catch (FlavorDomainException e) {
        throw new HumanReadableException(
            e,
            "%s: Apple test requires an Apple platform, found '%s'",
            buildTarget,
            cxxPlatform.getFlavor().getName());
      }
    }

    Optional<TestHostInfo> testHostInfo;
    if (args.getTestHostApp().isPresent()) {
      testHostInfo =
          Optional.of(
              createTestHostInfo(
                  buildTarget,
                  resolver,
                  args.getTestHostApp().get(),
                  debugFormat,
                  libraryFlavors,
                  cxxPlatforms));
    } else {
      testHostInfo = Optional.empty();
    }

    BuildTarget libraryTarget =
        buildTarget
            .withAppendedFlavors(extraFlavorsBuilder.build())
            .withAppendedFlavors(debugFormat.getFlavor())
            .withAppendedFlavors(LinkerMapMode.NO_LINKER_MAP.getFlavor());
    BuildRule library =
        createTestLibraryRule(
            targetGraph,
            projectFilesystem,
            params,
            resolver,
            cellRoots,
            args,
            testHostInfo.map(TestHostInfo::getTestHostAppBinarySourcePath),
            testHostInfo.map(TestHostInfo::getBlacklist).orElse(ImmutableSet.of()),
            libraryTarget,
            Optionals.toStream(args.getTestHostApp()).toImmutableSortedSet(Ordering.natural()));
    if (!createBundle || SwiftLibraryDescription.isSwiftTarget(libraryTarget)) {
      return library;
    }

    String platformName = appleCxxPlatform.getAppleSdk().getApplePlatform().getName();

    AppleBundle bundle =
        AppleDescriptions.createAppleBundle(
            cxxPlatformFlavorDomain,
            defaultCxxFlavor,
            appleCxxPlatformFlavorDomain,
            targetGraph,
            buildTarget.withAppendedFlavors(
                BUNDLE_FLAVOR,
                debugFormat.getFlavor(),
                LinkerMapMode.NO_LINKER_MAP.getFlavor(),
                AppleDescriptions.NO_INCLUDE_FRAMEWORKS_FLAVOR),
            projectFilesystem,
            params.withDeclaredDeps(
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .add(library)
                    .addAll(params.getDeclaredDeps().get())
                    .build()),
            resolver,
            codeSignIdentityStore,
            provisioningProfileStore,
            library.getBuildTarget(),
            args.getExtension(),
            Optional.empty(),
            args.getInfoPlist(),
            args.getInfoPlistSubstitutions(),
            args.getDeps(),
            args.getTests(),
            debugFormat,
            appleConfig.useDryRunCodeSigning(),
            appleConfig.cacheBundlesAndPackages(),
            appleConfig.assetCatalogValidation());
    resolver.addToIndex(bundle);

    Optional<SourcePath> xctool = getXctool(projectFilesystem, params, resolver);

    return new AppleTest(
        xctool,
        appleConfig.getXctoolStutterTimeoutMs(),
        appleCxxPlatform.getXctest(),
        appleConfig.getXctestPlatformNames().contains(platformName),
        platformName,
        appleConfig.getXctoolDefaultDestinationSpecifier(),
        Optional.of(args.getDestinationSpecifier()),
        buildTarget,
        projectFilesystem,
        params.withDeclaredDeps(ImmutableSortedSet.of(bundle)).withoutExtraDeps(),
        bundle,
        testHostInfo.map(TestHostInfo::getTestHostApp),
        args.getContacts(),
        args.getLabels(),
        args.getRunTestSeparately(),
        xcodeDeveloperDirectorySupplier,
        appleConfig.getTestLogDirectoryEnvironmentVariable(),
        appleConfig.getTestLogLevelEnvironmentVariable(),
        appleConfig.getTestLogLevel(),
        args.getTestRuleTimeoutMs().map(Optional::of).orElse(defaultTestRuleTimeoutMs),
        args.getIsUiTest(),
        args.getSnapshotReferenceImagesPath());
  }

  private Optional<SourcePath> getXctool(
      ProjectFilesystem projectFilesystem, BuildRuleParams params, BuildRuleResolver resolver) {
    // If xctool is specified as a build target in the buck config, it's wrapping ZIP file which
    // we need to unpack to get at the actual binary.  Otherwise, if it's specified as a path, we
    // can use that directly.
    if (appleConfig.getXctoolZipTarget().isPresent()) {
      final BuildRule xctoolZipBuildRule = resolver.getRule(appleConfig.getXctoolZipTarget().get());
      BuildTarget unzipXctoolTarget =
          xctoolZipBuildRule.getBuildTarget().withAppendedFlavors(UNZIP_XCTOOL_FLAVOR);
      final Path outputDirectory =
          BuildTargets.getGenPath(projectFilesystem, unzipXctoolTarget, "%s/unzipped");
      if (!resolver.getRuleOptional(unzipXctoolTarget).isPresent()) {
        BuildRuleParams unzipXctoolParams =
            params.withDeclaredDeps(ImmutableSortedSet.of(xctoolZipBuildRule)).withoutExtraDeps();
        resolver.addToIndex(
            new AbstractBuildRuleWithDeclaredAndExtraDeps(
                unzipXctoolTarget, projectFilesystem, unzipXctoolParams) {
              @Override
              public ImmutableList<Step> getBuildSteps(
                  BuildContext context, BuildableContext buildableContext) {
                buildableContext.recordArtifact(outputDirectory);
                return new ImmutableList.Builder<Step>()
                    .addAll(
                        MakeCleanDirectoryStep.of(
                            BuildCellRelativePath.fromCellRelativePath(
                                context.getBuildCellRootPath(),
                                getProjectFilesystem(),
                                outputDirectory)))
                    .add(
                        new UnzipStep(
                            getProjectFilesystem(),
                            context
                                .getSourcePathResolver()
                                .getAbsolutePath(
                                    Preconditions.checkNotNull(
                                        xctoolZipBuildRule.getSourcePathToOutput())),
                            outputDirectory))
                    .build();
              }

              @Override
              public SourcePath getSourcePathToOutput() {
                return new ExplicitBuildTargetSourcePath(getBuildTarget(), outputDirectory);
              }
            });
      }
      return Optional.of(
          new ExplicitBuildTargetSourcePath(
              unzipXctoolTarget, outputDirectory.resolve("bin/xctool")));
    } else if (appleConfig.getXctoolPath().isPresent()) {
      return Optional.of(new PathSourcePath(projectFilesystem, appleConfig.getXctoolPath().get()));
    } else {
      return Optional.empty();
    }
  }

  private BuildRule createTestLibraryRule(
      TargetGraph targetGraph,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      AppleTestDescriptionArg args,
      Optional<SourcePath> testHostAppBinarySourcePath,
      ImmutableSet<BuildTarget> blacklist,
      BuildTarget libraryTarget,
      ImmutableSortedSet<BuildTarget> extraCxxDeps) {
    BuildTarget existingLibraryTarget =
        libraryTarget
            .withAppendedFlavors(AppleDebuggableBinary.RULE_FLAVOR, CxxStrip.RULE_FLAVOR)
            .withAppendedFlavors(StripStyle.NON_GLOBAL_SYMBOLS.getFlavor());
    Optional<BuildRule> existingLibrary = resolver.getRuleOptional(existingLibraryTarget);
    BuildRule library;
    if (existingLibrary.isPresent()) {
      library = existingLibrary.get();
    } else {
      library =
          appleLibraryDescription.createLibraryBuildRule(
              targetGraph,
              libraryTarget,
              projectFilesystem,
              params,
              resolver,
              cellRoots,
              args,
              // For now, instead of building all deps as dylibs and fixing up their install_names,
              // we'll just link them statically.
              Optional.of(Linker.LinkableDepType.STATIC),
              testHostAppBinarySourcePath,
              blacklist,
              extraCxxDeps,
              CxxLibraryDescription.TransitiveCxxPreprocessorInputFunction.fromDeps());
      resolver.addToIndex(library);
    }
    return library;
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractAppleTestDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    // TODO(beng, coneko): This should technically only be a runtime dependency;
    // doing this adds it to the extra deps in BuildRuleParams passed to
    // the bundle and test rule.
    Optional<BuildTarget> xctoolZipTarget = appleConfig.getXctoolZipTarget();
    if (xctoolZipTarget.isPresent()) {
      extraDepsBuilder.add(xctoolZipTarget.get());
    }
    extraDepsBuilder.addAll(appleConfig.getCodesignProvider().getParseTimeDeps());
    appleLibraryDescription.findDepsForTargetFromConstructorArgs(
        buildTarget, cellRoots, constructorArg, extraDepsBuilder, targetGraphOnlyDepsBuilder);
  }

  private TestHostInfo createTestHostInfo(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      BuildTarget testHostAppBuildTarget,
      AppleDebugFormat debugFormat,
      Iterable<Flavor> additionalFlavors,
      ImmutableList<CxxPlatform> cxxPlatforms) {
    BuildRule rule =
        resolver.requireRule(
            testHostAppBuildTarget.withAppendedFlavors(
                ImmutableSet.<Flavor>builder()
                    .addAll(additionalFlavors)
                    .add(debugFormat.getFlavor(), StripStyle.NON_GLOBAL_SYMBOLS.getFlavor())
                    .build()));

    if (!(rule instanceof AppleBundle)) {
      throw new HumanReadableException(
          "Apple test rule '%s' has test_host_app '%s' not of type '%s'.",
          buildTarget,
          testHostAppBuildTarget,
          Description.getBuildRuleType(AppleBundleDescription.class));
    }

    AppleBundle testHostApp = (AppleBundle) rule;
    SourcePath testHostAppBinarySourcePath =
        testHostApp.getBinaryBuildRule().getSourcePathToOutput();

    ImmutableMap<BuildTarget, NativeLinkable> roots =
        NativeLinkables.getNativeLinkableRoots(
            testHostApp.getBinary().get().getBuildDeps(), x -> true);

    // Union the blacklist of all the platforms. This should give a superset for each particular
    // platform, which should be acceptable as items in the blacklist thare are unmatched are simply
    // ignored.
    ImmutableSet.Builder<BuildTarget> blacklistBuilder = ImmutableSet.builder();
    for (CxxPlatform platform : cxxPlatforms) {
      blacklistBuilder.addAll(
          NativeLinkables.getTransitiveNativeLinkables(platform, roots.values()).keySet());
    }
    return TestHostInfo.of(testHostApp, testHostAppBinarySourcePath, blacklistBuilder.build());
  }

  @Override
  public <U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      AppleTestDescriptionArg args,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      Class<U> metadataClass) {
    return appleLibraryDescription.createMetadataForLibrary(
        buildTarget, resolver, cellRoots, selectedVersions, args, metadataClass);
  }

  @Value.Immutable
  @BuckStyleTuple
  interface AbstractTestHostInfo {
    AppleBundle getTestHostApp();

    /**
     * Location of the test host binary that can be passed as the "bundle loader" option when
     * linking the test library.
     */
    SourcePath getTestHostAppBinarySourcePath();

    /** Libraries included in test host that should not be linked into the test library. */
    ImmutableSet<BuildTarget> getBlacklist();
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractAppleTestDescriptionArg
      extends AppleNativeTargetDescriptionArg, HasAppleBundleFields, HasContacts, HasTestTimeout {
    @Value.Default
    default boolean getRunTestSeparately() {
      return false;
    }

    @Value.Default
    default boolean getIsUiTest() {
      return false;
    }

    Optional<BuildTarget> getTestHostApp();

    // for use with FBSnapshotTestcase, injects the path as FB_REFERENCE_IMAGE_DIR
    Optional<Either<SourcePath, String>> getSnapshotReferenceImagesPath();

    // Bundle related fields.
    ImmutableMap<String, String> getDestinationSpecifier();

    @Override
    default Either<AppleBundleExtension, String> getExtension() {
      return Either.ofLeft(AppleBundleExtension.XCTEST);
    }

    @Override
    default Optional<String> getProductName() {
      return Optional.empty();
    }
  }
}
