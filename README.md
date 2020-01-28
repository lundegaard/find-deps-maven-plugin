# Find Dependencies Maven Plugin

Maven plugin which collects all dependencies and used plugins from all modules in the multi-module project and puts them into single POM file in the root project. This can be later effectively used for downloading the dependencies using the _Maven dependency plugin_ or for using Maven inside Docker. The main goal is to make repeating builds of a Maven project in Docker environment quicker, but can be used whenever one needs to download all dependencies based on single Maven POM file.

## Usage

Add the plugin into your top-level pom.xml (plugin checks for being in the top-level project in the reactor) like this:

```xml
<project>
    ...
    <build>
        <pluginManagement>
            <plugins>
                ...
                <plugin>
                    <groupId>eu.lundegaard.maven</groupId>
                    <artifactId>find-deps-maven-plugin</artifactId>
                    <version>[LATEST-VERSION-FROM-GITHUB]</version>
                </plugin>
                ...
            </plugins>
        </pluginManagement>

        <plugins>
            ...
            <plugin>
                <groupId>eu.lundegaard.maven</groupId>
                <artifactId>find-deps-maven-plugin</artifactId>
                <executions>
                    <execution>
                        <phase>generate-resources</phase>
                        <goals>
                            <goal>find-deps</goal>
                        </goals>
                    </execution>
                </executions>
                <configuration>
                    <includeOnlyRepoIds>
                        <includeOnlyRepoId>lundegaard-public</includeOnlyRepoId>
                        <includeOnlyRepoId>lundegaard-snapshots</includeOnlyRepoId>
                    </includeOnlyRepoIds>
                </configuration>
            </plugin>
            ...
        </plugins>
    </build>
</project>
```

After adding plugin into your POM whenever you build the project the `pom-dependencies.xml` file is refreshed and if changed it should be placed under version control.

Then the usage in a Dockerfile is following:

```Dockerfile
# ...

WORKDIR /work

# Copy pom-dependencies.xml only, which can be easily cached into a layer
COPY pom-dependencies.xml /work

# Gather all dependencies - this can be easily cached into a layer as well
RUN mvn -f pom-dependencies.xml org.apache.maven.plugins:maven-dependency-plugin:3.1.1:go-offline

# Continue with your standard build
# ...
```

## Excluding reactor projects as dependencies

At the moment **all dependencies with the same `groupId` as your top-level project are excluded**.


## Excluding repos

The plugin uses mechanisms similar to obtaining effective POM and thus uses project info preprocessed by Maven. This also leads to usage of configuration from `settings.xml` file and developers often have additional repos set here. If you want to exclude or limit usage of such repos you can use one of the following configurations

Use `includeOnlyRepoIds` or `includeOnlyRepoUrls` configuration to limit the repos only on the defined set of repositories (by repository ID or URL), e. g.

```xml
<configuration>
    <includeOnlyRepoIds>
        <includeOnlyRepoId>lundegaard-public</includeOnlyRepoId>
        <includeOnlyRepoId>lundegaard-snapshots</includeOnlyRepoId>
    </includeOnlyRepoIds>
</configuration>
```

Use `excludeRepoIds` or `excludeRepoUrls` configuration to exclude repos by repository ID or URL, e. g.

```xml
<configuration>
    <excludeRepoIds>
        <excludeRepoId>central</excludeRepoId>
    </excludeRepoIds>
</configuration>
```

## Adding more artifacts

Additional dependencies are handy when some artifacts are not resolved automatically by the plugin. This can be because it not mentioned directly in the POM (e. g. `maven-surefire-plugin` adds some dependencies dynamically based on used testing framework) or is preprocessed by Maven (e. g. dependencies with the `import` scope).

The artifacts are added by their GAV coordinates in the format `groupId:artifactId:version[:type[:classifier]]`. The `type` and `classifier` are optional.

```xml
<configuration>
    <additionalArtifacts>
        <additionalArtifact>com.liferay.portal:release.portal.bom:7.2.0:pom</additionalArtifact>
    </additionalArtifacts>
</configuration>
```
