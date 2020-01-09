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

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;
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
import java.io.StringWriter;
import java.util.Comparator;
import java.util.HashMap;
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

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {

            doExecute();

        } catch (IOException e) {
            throw new MojoFailureException("Error while running plugin.", e);
        } catch (TemplateException te) {
            throw new MojoExecutionException("Error while running plugin.", te);
        }
    }

    private void doExecute() throws IOException, TemplateException {
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
            List<Dependency> dependencies, List<Plugin> plugins) throws IOException, TemplateException {

        Configuration freemarkerConfiguration = new Configuration(Configuration.VERSION_2_3_29);
        freemarkerConfiguration.setClassForTemplateLoading(this.getClass(), "/templates");
        freemarkerConfiguration.setDefaultEncoding("UTF-8");
        freemarkerConfiguration.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        freemarkerConfiguration.setLogTemplateExceptions(false);
        freemarkerConfiguration.setWrapUncheckedExceptions(true);
        freemarkerConfiguration.setFallbackOnNullLoopVariable(false);
        freemarkerConfiguration.setWhitespaceStripping(true);

        Map<String, Object> freemarkerData = new HashMap<>();
        freemarkerData.put("project", project);
        freemarkerData.put("repositories", repositories);
        freemarkerData.put("pluginRepositories", pluginRepositories);
        freemarkerData.put("dependencies", dependencies);
        freemarkerData.put("plugins", plugins);

        Template template = freemarkerConfiguration.getTemplate("pom-dependencies.xml.ftl");

        StringWriter output = new StringWriter();
        template.process(freemarkerData, output);

        return output.toString();
    }
}
