package io.quarkus.gradle.dependency;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.UnknownDomainObjectException;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.attributes.Category;
import org.gradle.api.capabilities.Capability;
import org.gradle.api.initialization.IncludedBuild;
import org.gradle.api.plugins.JavaPlugin;

import io.quarkus.bootstrap.BootstrapConstants;
import io.quarkus.bootstrap.model.AppArtifactCoords;
import io.quarkus.bootstrap.model.AppArtifactKey;
import io.quarkus.bootstrap.util.BootstrapUtils;
import io.quarkus.bootstrap.util.ZipUtils;

public class DependencyUtils {

    private static final String COPY_CONFIGURATION_NAME = "quarkusDependency";
    private static final String TEST_FIXTURE_SUFFIX = "-test-fixtures";

    public static Configuration duplicateConfiguration(Project project, Configuration... toDuplicates) {
        Configuration configurationCopy = project.getConfigurations().findByName(COPY_CONFIGURATION_NAME);
        if (configurationCopy != null) {
            project.getConfigurations().remove(configurationCopy);
        }
        configurationCopy = project.getConfigurations().create(COPY_CONFIGURATION_NAME);

        // We add boms for dependency resolution
        List<Dependency> boms = getEnforcedPlatforms(project);
        configurationCopy.getDependencies().addAll(boms);

        for (Configuration toDuplicate : toDuplicates) {
            for (Dependency dependency : toDuplicate.getAllDependencies()) {
                if (includedBuild(project, dependency.getName()) != null) {
                    continue;
                }
                if (isTestFixtureDependency(dependency)) {
                    continue;
                }
                configurationCopy.getDependencies().add(dependency);
            }
        }
        return configurationCopy;
    }

    public static Dependency create(DependencyHandler dependencies, String conditionalDependency) {
        AppArtifactCoords dependencyCoords = AppArtifactCoords.fromString(conditionalDependency);
        return dependencies.create(String.join(":", dependencyCoords.getGroupId(), dependencyCoords.getArtifactId(),
                dependencyCoords.getVersion()));
    }

    public static boolean exist(Set<ResolvedArtifact> runtimeArtifacts, List<AppArtifactKey> dependencies) {
        final Set<AppArtifactKey> rtKeys = new HashSet<>(runtimeArtifacts.size());
        runtimeArtifacts.forEach(r -> rtKeys.add(
                new AppArtifactKey(r.getModuleVersion().getId().getGroup(), r.getName(), r.getClassifier(), r.getExtension())));
        return rtKeys.containsAll(dependencies);
    }

    public static boolean exists(Set<ResolvedArtifact> runtimeArtifacts, Dependency dependency) {
        for (ResolvedArtifact runtimeArtifact : runtimeArtifacts) {
            ModuleVersionIdentifier artifactId = runtimeArtifact.getModuleVersion().getId();
            if (artifactId.getGroup().equals(dependency.getGroup()) && artifactId.getName().equals(dependency.getName())) {
                return true;
            }
        }
        return false;
    }

    public static Set<ExtensionDependency> loadQuarkusExtension(Project project, Configuration configuration) {
        Set<ExtensionDependency> extensions = new HashSet<>();
        Configuration configurationCopy = duplicateConfiguration(project, configuration);

        Set<ResolvedArtifact> resolvedArtifacts = configurationCopy.getResolvedConfiguration().getResolvedArtifacts();
        for (ResolvedArtifact artifact : resolvedArtifacts) {
            ExtensionDependency extension = getExtensionInfoOrNull(project, artifact);
            if (extension != null) {
                extensions.add(extension);
            }
        }

        return extensions;
    }

    public static boolean isTestFixtureDependency(Dependency dependency) {
        if (!(dependency instanceof ModuleDependency)) {
            return false;
        }
        ModuleDependency module = (ModuleDependency) dependency;
        for (Capability requestedCapability : module.getRequestedCapabilities()) {
            if (requestedCapability.getName().endsWith(TEST_FIXTURE_SUFFIX)) {
                return true;
            }
        }
        return false;
    }

    public static List<Dependency> getEnforcedPlatforms(Project project) {
        final List<org.gradle.api.artifacts.Dependency> directExtension = new ArrayList<>();
        final Configuration impl = project.getConfigurations()
                .getByName(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME);

        for (Dependency d : impl.getAllDependencies()) {
            if (!(d instanceof ModuleDependency)) {
                continue;
            }
            final ModuleDependency module = (ModuleDependency) d;
            if (isEnforcedPlatform(module)) {
                directExtension.add(d);
            }
        }
        return directExtension;
    }

    public static boolean isEnforcedPlatform(ModuleDependency module) {
        final Category category = module.getAttributes().getAttribute(Category.CATEGORY_ATTRIBUTE);
        return category != null && (Category.ENFORCED_PLATFORM.equals(category.getName())
                || Category.REGULAR_PLATFORM.equals(category.getName()));
    }

    public static IncludedBuild includedBuild(final Project project, final String projectName) {
        try {
            return project.getGradle().includedBuild(projectName);
        } catch (UnknownDomainObjectException ignore) {
            return null;
        }
    }

    public static String asDependencyNotation(Dependency dependency) {
        return String.join(":", dependency.getGroup(), dependency.getName(), dependency.getVersion());
    }

    public static String asDependencyNotation(AppArtifactCoords artifactCoords) {
        return String.join(":", artifactCoords.getGroupId(), artifactCoords.getArtifactId(), artifactCoords.getVersion());
    }

    public static String asCapabilityNotation(AppArtifactCoords artifactCoords) {
        return String.join(":", artifactCoords.getGroupId(), artifactCoords.getArtifactId() + "-capability",
                artifactCoords.getVersion());
    }

    public static String asFeatureName(ModuleVersionIdentifier version) {
        return version.getGroup() + ":" + version.getName();
    }

    public static String asFeatureName(Dependency version) {
        return version.getGroup() + ":" + version.getName();
    }

    public static ExtensionDependency getExtensionInfoOrNull(Project project, ResolvedArtifact artifact) {
        ModuleVersionIdentifier artifactId = artifact.getModuleVersion().getId();
        File artifactFile = artifact.getFile();
        if (!artifactFile.exists() || !"jar".equals(artifact.getExtension())) {
            return null;
        }
        if (artifactFile.isDirectory()) {
            Path descriptorPath = artifactFile.toPath().resolve(BootstrapConstants.DESCRIPTOR_PATH);
            if (Files.exists(descriptorPath)) {
                return loadExtensionInfo(project, descriptorPath, artifactId);
            }
        } else {
            try (FileSystem artifactFs = ZipUtils.newFileSystem(artifactFile.toPath())) {
                Path descriptorPath = artifactFs.getPath(BootstrapConstants.DESCRIPTOR_PATH);
                if (Files.exists(descriptorPath)) {
                    return loadExtensionInfo(project, descriptorPath, artifactId);
                }
            } catch (IOException e) {
                throw new GradleException("Failed to read " + artifactFile, e);
            }
        }
        return null;
    }

    private static ExtensionDependency loadExtensionInfo(Project project, Path descriptorPath,
            ModuleVersionIdentifier exentionId) {
        final Properties extensionProperties = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(descriptorPath)) {
            extensionProperties.load(reader);
        } catch (IOException e) {
            throw new GradleException("Failed to load " + descriptorPath, e);
        }
        AppArtifactCoords deploymentModule = AppArtifactCoords
                .fromString(extensionProperties.getProperty(BootstrapConstants.PROP_DEPLOYMENT_ARTIFACT));
        final List<Dependency> conditionalDependencies;
        if (extensionProperties.containsKey(BootstrapConstants.CONDITIONAL_DEPENDENCIES)) {
            final String[] deps = BootstrapUtils
                    .splitByWhitespace(extensionProperties.getProperty(BootstrapConstants.CONDITIONAL_DEPENDENCIES));
            conditionalDependencies = new ArrayList<>(deps.length);
            for (String conditionalDep : deps) {
                conditionalDependencies.add(DependencyUtils.create(project.getDependencies(), conditionalDep));
            }
        } else {
            conditionalDependencies = Collections.emptyList();
        }

        final AppArtifactKey[] constraints = BootstrapUtils
                .parseDependencyCondition(extensionProperties.getProperty(BootstrapConstants.DEPENDENCY_CONDITION));
        return new ExtensionDependency(exentionId, deploymentModule, conditionalDependencies,
                constraints == null ? Collections.emptyList() : Arrays.asList(constraints));
    }
}
