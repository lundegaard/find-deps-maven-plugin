/*
 * Copyright (C) 2020 Lundegaard a.s., All Rights Reserved
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; version 3.0 of the License.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 * https://www.gnu.org/licenses/lgpl-3.0.html
 */
package eu.lundegaard.maven.finddeps;

import org.apache.commons.io.FileUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Repository;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * @author Ales Rybak(ales.rybak@lundegaard.eu)
 */
@Mojo(
        name = "find-deps",
        requiresDependencyResolution = ResolutionScope.TEST)
public class FindDepsMojo extends AbstractMojo {

    private static final Logger LOG = LoggerFactory.getLogger(FindDepsMojo.class);

    /**
     * The Maven project.
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    /**
     * Contains the full list of projects in the reactor.
     */
    @Parameter(defaultValue = "${reactorProjects}", readonly = true, required = true)
    private List<MavenProject> reactorProjects;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {

            doExecute();

        } catch (IOException e) {
            throw new MojoFailureException("Error while running plugin.", e);
        }
    }

    private void doExecute() throws IOException {
        if (project.toString().equals(session.getTopLevelProject().toString())) {
            List<MavenProject> allProjects = session.getAllProjects();

            List<Repository> repositories = gatherRepositories(allProjects);
            List<Repository> pluginRepositories = gatherPluginRepositories(allProjects);
            List<Dependency> dependencies = gatherDependencies(allProjects);
            List<Plugin> buildPlugins = gatherBuildPlugins(allProjects);

            String pom = producePom(repositories, pluginRepositories, dependencies, buildPlugins);

            File depsPomFile = new File(project.getBasedir(), "pom-dependencies.xml");
            FileUtils.write(depsPomFile, pom, UTF_8);

            LOG.info("Dependencies POM file: {}", depsPomFile);
            LOG.info("Repositories count: {}", repositories.size());
            LOG.info("Plugin Repositories count: {}", pluginRepositories.size());
            LOG.info("Dependencies count: {}", dependencies.size());
            LOG.info("Plugins count: {}", buildPlugins.size());
        } else {
            LOG.info("Not top-level project - skipping.");
        }
    }

    private List<Plugin> gatherBuildPlugins(List<MavenProject> projects) {
        return projects.stream()
                .flatMap(p -> p.getBuildPlugins().stream())
                .filter(distinctByKey(p -> String.join(":", p.getGroupId(), p.getArtifactId(), p.getVersion())))
                .sorted(Comparator.comparing(Plugin::getGroupId)
                        .thenComparing(Plugin::getArtifactId)
                        .thenComparing(Plugin::getVersion))
                .collect(Collectors.toList());
    }

    private List<Repository> gatherRepositories(List<MavenProject> projects) {
        return projects.stream()
                .flatMap(p -> p.getRepositories().stream())
                .distinct()
                .collect(Collectors.toList());
    }

    private List<Repository> gatherPluginRepositories(List<MavenProject> projects) {
        return projects.stream()
                .flatMap(p -> p.getPluginRepositories().stream())
                .distinct()
                .collect(Collectors.toList());
    }

    private List<Dependency> gatherDependencies(List<MavenProject> projects) {
        return projects.stream()
                .flatMap(p -> p.getDependencies().stream())
                .filter(dependency -> !dependency.getGroupId().equals(project.getGroupId()))
                .filter(distinctByKey(d -> String.join(":", d.getGroupId(), d.getArtifactId(), d.getVersion())))
                .sorted(Comparator.comparing(Dependency::getGroupId)
                        .thenComparing(Dependency::getArtifactId)
                        .thenComparing(Dependency::getVersion))
                .collect(Collectors.toList());
    }

    public static <T> Predicate<T> distinctByKey(Function<? super T, Object> keyExtractor) {
        Map<Object, Boolean> seen = new ConcurrentHashMap<>();
        return t -> seen.putIfAbsent(keyExtractor.apply(t), Boolean.TRUE) == null;
    }

    private String producePom(List<Repository> repositories, List<Repository> pluginRepositories,
            List<Dependency> dependencies, List<Plugin> plugins) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\" ")
                .append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
                .append("xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 ")
                .append("http://maven.apache.org/maven-v4_0_0.xsd\">\n");
        sb.append("    <modelVersion>4.0.0</modelVersion>\n");
        sb.append("    <groupId>").append(project.getGroupId()).append("</groupId>\n");
        sb.append("    <artifactId>").append(project.getArtifactId()).append("-dependencies</artifactId>\n");
        sb.append("    <version>").append(project.getVersion()).append("</version>\n");
        sb.append("    <packaging>jar</packaging>\n");
        sb.append("    <name>").append(project.getName()).append(" [Dependencies]</name>\n");
        sb.append("    <description>Dependencies POM for the '")
                .append(project.getName())
                .append("'. Use it together with `mvn dependency:go-offline`.</description>\n");

        if (!repositories.isEmpty()) {
            sb.append("    <repositories>\n");
            for (Repository repository : repositories) {
                sb.append("        <repository>\n");
                sb.append("            <id>").append(repository.getId()).append("</id>\n");
                sb.append("            <name>").append(repository.getName()).append("</name>\n");
                sb.append("            <url>").append(repository.getUrl()).append("</url>\n");
                if (repository.getReleases() != null && repository.getReleases().getEnabled() != null) {
                    sb.append("            <releases>\n");
                    sb.append("                <enabled>")
                            .append(repository.getReleases().getEnabled())
                            .append("</enabled>\n");
                    sb.append("            </releases>\n");
                }
                if (repository.getSnapshots() != null && repository.getSnapshots().getEnabled() != null) {
                    sb.append("            <snapshots>\n");
                    sb.append("                <enabled>")
                            .append(repository.getSnapshots().getEnabled())
                            .append("</enabled>\n");
                    sb.append("            </snapshots>\n");
                }
                sb.append("        </repository>\n");
            }
            sb.append("    </repositories>\n");
        }

        if (!pluginRepositories.isEmpty()) {
            sb.append("    <pluginRepositories>\n");
            for (Repository repository : pluginRepositories) {
                sb.append("        <pluginRepository>\n");
                sb.append("            <id>").append(repository.getId()).append("</id>\n");
                sb.append("            <name>").append(repository.getName()).append("</name>\n");
                sb.append("            <url>").append(repository.getUrl()).append("</url>\n");
                if (repository.getReleases() != null && repository.getReleases().getEnabled() != null) {
                    sb.append("            <releases>\n");
                    sb.append("                <enabled>")
                            .append(repository.getReleases().getEnabled())
                            .append("</enabled>\n");
                    sb.append("            </releases>\n");
                }
                if (repository.getSnapshots() != null && repository.getSnapshots().getEnabled() != null) {
                    sb.append("            <snapshots>\n");
                    sb.append("                <enabled>")
                            .append(repository.getSnapshots().getEnabled())
                            .append("</enabled>\n");
                    sb.append("            </snapshots>\n");
                }
                sb.append("        </pluginRepository>\n");
            }
            sb.append("    </pluginRepositories>\n");
        }

        sb.append("    <dependencies>\n");
        for (Dependency dependency : dependencies) {
            sb.append("        <dependency>\n");
            sb.append("            <groupId>").append(dependency.getGroupId()).append("</groupId>\n");
            sb.append("            <artifactId>").append(dependency.getArtifactId()).append("</artifactId>\n");
            sb.append("            <version>").append(dependency.getVersion()).append("</version>\n");
            sb.append("        </dependency>\n");
        }
        sb.append("    </dependencies>\n");

        sb.append("    <build>\n");
        sb.append("        <plugins>\n");
        for (Plugin plugin : plugins) {
            List<Dependency> pluginDependencies = plugin.getDependencies();
            sb.append("            <plugin>\n");
            sb.append("                <groupId>").append(plugin.getGroupId()).append("</groupId>\n");
            sb.append("                <artifactId>").append(plugin.getArtifactId()).append("</artifactId>\n");
            sb.append("                <version>").append(plugin.getVersion()).append("</version>\n");
            if (!pluginDependencies.isEmpty()) {
                sb.append("                <dependencies>\n");
                for (Dependency dependency : pluginDependencies) {
                    sb.append("                    <dependency>\n");
                    sb.append("                        <groupId>")
                            .append(dependency.getGroupId())
                            .append("</groupId>\n");
                    sb.append("                        <artifactId>")
                            .append(dependency.getArtifactId())
                            .append("</artifactId>\n");
                    sb.append("                        <version>")
                            .append(dependency.getVersion())
                            .append("</version>\n");
                    sb.append("                    </dependency>\n");
                }
                sb.append("                </dependencies>\n");
            }
            sb.append("            </plugin>\n");
        }
        sb.append("        </plugins>\n");
        sb.append("    </build>\n");
        sb.append("</project>\n");

        return sb.toString();
    }


}
