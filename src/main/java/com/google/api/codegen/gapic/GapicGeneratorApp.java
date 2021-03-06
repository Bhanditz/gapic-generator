/* Copyright 2016 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.api.codegen.gapic;

import com.google.api.codegen.ArtifactType;
import com.google.api.codegen.ConfigProto;
import com.google.api.codegen.common.CodeGenerator;
import com.google.api.codegen.common.GeneratedResult;
import com.google.api.codegen.common.TargetLanguage;
import com.google.api.codegen.config.ApiDefaultsConfig;
import com.google.api.codegen.config.DependenciesConfig;
import com.google.api.codegen.config.GapicProductConfig;
import com.google.api.codegen.config.PackageMetadataConfig;
import com.google.api.codegen.config.PackagingConfig;
import com.google.api.codegen.util.MultiYamlReader;
import com.google.api.tools.framework.model.ConfigSource;
import com.google.api.tools.framework.model.Diag;
import com.google.api.tools.framework.model.SimpleLocation;
import com.google.api.tools.framework.model.stages.Merged;
import com.google.api.tools.framework.tools.ToolDriverBase;
import com.google.api.tools.framework.tools.ToolOptions;
import com.google.api.tools.framework.tools.ToolOptions.Option;
import com.google.api.tools.framework.tools.ToolUtil;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.inject.TypeLiteral;
import com.google.longrunning.OperationsProto;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.Message;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Main class for the code generator. */
public class GapicGeneratorApp extends ToolDriverBase {
  public static final Option<String> LANGUAGE =
      ToolOptions.createOption(String.class, "language", "The target language.", "");
  public static final Option<String> OUTPUT_FILE =
      ToolOptions.createOption(
          String.class,
          "output_file",
          "The name of the output file or folder to put generated code.",
          "");
  public static final Option<String> PROTO_PACKAGE =
      ToolOptions.createOption(
          String.class,
          "proto_package",
          "The proto package designating the files actually intended for output.\n"
              + "This option is required if the GAPIC generator config files are not given.",
          "");
  public static final Option<String> CLIENT_PACKAGE =
      ToolOptions.createOption(
          String.class,
          "client_package",
          "The desired package name for the generated Java client. \n",
          "");

  public static final Option<List<String>> GENERATOR_CONFIG_FILES =
      ToolOptions.createOption(
          new TypeLiteral<List<String>>() {},
          "config_files",
          "The list of YAML configuration files for the code generator.",
          ImmutableList.of());

  public static final Option<String> PACKAGE_CONFIG2_FILE =
      ToolOptions.createOption(String.class, "package_config2", "The packaging configuration.", "");

  public static final Option<List<String>> ENABLED_ARTIFACTS =
      ToolOptions.createOption(
          new TypeLiteral<List<String>>() {},
          "enabled_artifacts",
          "The artifacts to be generated by the code generator.",
          ImmutableList.of());

  public static final Option<Boolean> DEV_SAMPLES =
      ToolOptions.createOption(
          Boolean.class,
          "dev_samples",
          "Whether to generate samples in non-production-ready languages.",
          false);

  private ArtifactType artifactType;

  /** Constructs a code generator api based on given options. */
  public GapicGeneratorApp(ToolOptions options, ArtifactType artifactType) {
    super(options);
    this.artifactType = artifactType;
  }

  @Override
  public ExtensionRegistry getPlatformExtensions() {
    ExtensionRegistry extensionRegistry = super.getPlatformExtensions();
    OperationsProto.registerAllExtensions(extensionRegistry);
    return extensionRegistry;
  }

  @Override
  protected void process() throws Exception {

    String protoPackage = Strings.emptyToNull(options.get(PROTO_PACKAGE));

    // Read the YAML config, it it was given, and convert it to proto.
    List<String> configFileNames = options.get(GENERATOR_CONFIG_FILES);
    ConfigProto configProto = null;
    if (configFileNames.size() > 0) {
      // Read the YAML config and convert it to proto.
      ConfigSource configSource = loadConfigFromFiles(configFileNames);
      if (configSource == null) {
        return;
      }

      configProto = (ConfigProto) configSource.getConfig();
      if (configProto == null) {
        return;
      }
    }

    model.establishStage(Merged.KEY);

    if (model.getDiagReporter().getDiagCollector().getErrorCount() > 0) {
      for (Diag diag : model.getDiagReporter().getDiagCollector().getDiags()) {
        System.err.println(diag.toString());
      }
      return;
    }

    PackageMetadataConfig packageConfig = null;
    if (!Strings.isNullOrEmpty(options.get(PACKAGE_CONFIG2_FILE))) {
      ApiDefaultsConfig apiDefaultsConfig = ApiDefaultsConfig.load();
      DependenciesConfig dependenciesConfig = DependenciesConfig.load();
      PackagingConfig packagingConfig = PackagingConfig.load(options.get(PACKAGE_CONFIG2_FILE));
      packageConfig =
          PackageMetadataConfig.createFromPackaging(
              apiDefaultsConfig, dependenciesConfig, packagingConfig);
    }

    TargetLanguage language;
    if (!Strings.isNullOrEmpty(options.get(LANGUAGE))) {
      language = TargetLanguage.fromString(options.get(LANGUAGE).toUpperCase());
    } else {
      if (configProto == null || Strings.isNullOrEmpty(configProto.getLanguage())) {
        throw new IllegalArgumentException(
            "Language not set by --language option or by gapic config.");
      }
      language = TargetLanguage.fromString(configProto.getLanguage().toUpperCase());
    }

    String clientPackage = Strings.emptyToNull(options.get(CLIENT_PACKAGE));

    GapicProductConfig productConfig =
        GapicProductConfig.create(model, configProto, protoPackage, clientPackage, language);
    if (productConfig == null) {
      return;
    }

    String outputPath = options.get(OUTPUT_FILE);
    ArtifactFlags artifactFlags = new ArtifactFlags(options.get(ENABLED_ARTIFACTS), artifactType);
    List<CodeGenerator<?>> generators =
        GapicGeneratorFactory.create(
            language, model, productConfig, packageConfig, artifactFlags, options.get(DEV_SAMPLES));
    ImmutableMap.Builder<String, Object> outputFiles = ImmutableMap.builder();
    ImmutableSet.Builder<String> executables = ImmutableSet.builder();
    for (CodeGenerator<?> generator : generators) {
      Map<String, ? extends GeneratedResult<?>> generatorResult = generator.generate();
      for (Map.Entry<String, ? extends GeneratedResult<?>> entry : generatorResult.entrySet()) {
        outputFiles.put(entry.getKey(), entry.getValue().getBody());
        if (entry.getValue().isExecutable()) {
          executables.add(entry.getKey());
        }
      }
    }
    writeCodeGenOutput(outputFiles.build(), outputPath);
    setOutputFilesPermissions(executables.build(), outputPath);
  }

  @VisibleForTesting
  void writeCodeGenOutput(Map<String, ?> outputFiles, String outputPath) throws IOException {
    // TODO: Support zip output.
    if (outputPath.endsWith(".jar") || outputPath.endsWith(".srcjar")) {
      ToolUtil.writeJar(outputFiles, outputPath);
    } else {
      ToolUtil.writeFiles(outputFiles, outputPath);
    }
  }

  @VisibleForTesting
  void setOutputFilesPermissions(Set<String> executables, String outputPath) {
    if (outputPath.endsWith(".jar")) {
      return;
    }
    for (String executable : executables) {
      File file =
          Strings.isNullOrEmpty(outputPath)
              ? new File(executable)
              : new File(outputPath, executable);
      if (!file.setExecutable(true, false)) {
        warning("Failed to set output file as executable. Probably running on a non-POSIX system.");
      }
    }
  }

  private ConfigSource loadConfigFromFiles(List<String> configFileNames) {
    List<File> configFiles = pathsToFiles(configFileNames);
    if (model.getDiagReporter().getDiagCollector().getErrorCount() > 0) {
      return null;
    }
    ImmutableMap<String, Message> supportedConfigTypes =
        ImmutableMap.of(
            ConfigProto.getDescriptor().getFullName(), ConfigProto.getDefaultInstance());
    return MultiYamlReader.read(
        model.getDiagReporter().getDiagCollector(), configFiles, supportedConfigTypes);
  }

  private List<File> pathsToFiles(List<String> configFileNames) {
    List<File> files = new ArrayList<>();

    for (String configFileName : configFileNames) {
      File file = model.findDataFile(configFileName);
      if (file == null) {
        error("Cannot find configuration file '%s'.", configFileName);
        continue;
      }
      files.add(file);
    }

    return files;
  }

  private void error(String message, Object... args) {
    model
        .getDiagReporter()
        .getDiagCollector()
        .addDiag(Diag.error(SimpleLocation.TOPLEVEL, message, args));
  }

  private void warning(String message, Object... args) {
    model
        .getDiagReporter()
        .getDiagCollector()
        .addDiag(Diag.warning(SimpleLocation.TOPLEVEL, message, args));
  }
}
