/*
 * Copyright (c) 2023, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.graalvm.python.maven.plugin;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.stream.Collectors;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.graalvm.python.embedding.utils.VFSUtils;
import org.graalvm.python.embedding.utils.GraalPyRunner;

@Mojo(name = "process-graalpy-resources", defaultPhase = LifecyclePhase.PROCESS_RESOURCES,
                requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME,
                requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class ManageResourcesMojo extends AbstractMojo {

    private static final String PYTHON_LANGUAGE = "python-language";
    private static final String PYTHON_RESOURCES = "python-resources";
    private static final String PYTHON_LAUNCHER = "python-launcher";
    private static final String GRAALPY_GROUP = "org.graalvm.python";

    private static final String GRAALPY_MAIN_CLASS = "com.oracle.graal.python.shell.GraalPythonMain";

    private static final boolean IS_WINDOWS = System.getProperty("os.name").startsWith("Windows");
    private static final String LAUNCHER = IS_WINDOWS ? "graalpy.exe" : "graalpy.sh";

    private static final String INCLUDE_PREFIX = "include:";

    private static final String EXCLUDE_PREFIX = "exclude:";

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    MavenProject project;

    @Parameter
    List<String> packages;

    @Parameter
    PythonHome pythonHome;

    static Path getHomeDirectory(MavenProject project) {
        return Path.of(project.getBuild().getOutputDirectory(), "vfs", "home");
    }

    public void execute() throws MojoExecutionException {
        manageHome();
        manageVenv();
        listGraalPyResources();
    }

    public static class PythonHome {
        private List<String> includes;
        private List<String> excludes;
    }

    private void manageHome() throws MojoExecutionException {
        var homeDirectory = getHomeDirectory(project);
        if (pythonHome == null) {
            delete(homeDirectory);
            return;
        }
        var tag = homeDirectory.resolve("tagfile");
        var graalPyVersion = getGraalPyVersion(project);

        List<String> pythonHomeIncludes = toSortedArrayList(pythonHome.includes);
        List<String> pythonHomeExcludes = toSortedArrayList(pythonHome.excludes);

        if (Files.isReadable(tag)) {
            List<String> lines = null;
            try {
                lines = Files.readAllLines(tag);
            } catch (IOException e) {
                throw new MojoExecutionException(String.format("failed to read tag file %s", tag), e);
            }
            if (lines.isEmpty() || !graalPyVersion.equals(lines.get(0))) {
                getLog().info(String.format("Stale GraalPy home, updating to %s", graalPyVersion));
                delete(homeDirectory);
            }
            if (pythonHomeChanged(pythonHomeIncludes, pythonHomeExcludes, lines)) {
                getLog().info(String.format("Deleting GraalPy home due to changed includes or excludes"));
                delete(homeDirectory);
            }
        } else {
            getLog().info(String.format("Creating GraalPy %s home", graalPyVersion));
        }
        try {
            if (!Files.exists(homeDirectory)) {
                Files.createDirectories(homeDirectory.getParent());
                VFSUtils.copyGraalPyHome(calculateClasspath(project), homeDirectory, pythonHomeIncludes, pythonHomeExcludes, new MavenDelegateLog(getLog()));
            }
            Files.write(tag, List.of(graalPyVersion), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            write(tag, pythonHomeIncludes, INCLUDE_PREFIX);
            write(tag, pythonHomeExcludes, EXCLUDE_PREFIX);
        } catch (IOException | InterruptedException e) {
            throw new MojoExecutionException(String.format("failed to copy graalpy home %s", homeDirectory), e);
        }
    }

    private boolean pythonHomeChanged(List<String> includes, List<String> excludes, List<String> lines) throws MojoExecutionException {
        List<String> prevIncludes = new ArrayList<>();
        List<String> prevExcludes = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.startsWith(INCLUDE_PREFIX)) {
                prevIncludes.add(l.substring(INCLUDE_PREFIX.length()));
            } else if (l.startsWith(EXCLUDE_PREFIX)) {
                prevExcludes.add(l.substring(EXCLUDE_PREFIX.length()));
            }
        }
        prevIncludes = toSortedArrayList(prevIncludes);
        prevExcludes = toSortedArrayList(prevExcludes);
        return !(prevIncludes.equals(includes) && prevExcludes.equals(excludes));
    }

    private void write(Path tag, List<String> list, String prefix) throws IOException {
        if(list != null) {
            Files.write(tag, list.stream().map(l -> prefix + l).collect(Collectors.toList()), StandardOpenOption.APPEND);
        }
    }

    private ArrayList<String> toSortedArrayList(List<String> l) {
        if(l != null) {
            Collections.sort(l);
            return new ArrayList<>(l);
        }
        return new ArrayList<>(0);
    }

    private void delete(Path homeDirectory) throws MojoExecutionException {
        try {
            try (var s = Files.walk(homeDirectory)) {
                s.sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        } catch (IOException e) {
            new MojoExecutionException(String.format("failed to delete %s", homeDirectory),  e);
        }
    }

    private void listGraalPyResources() throws MojoExecutionException {
        Path vfs = getVenvDirectory(project).getParent();
        try {
            VFSUtils.generateVFSFilesList(vfs);
        } catch(IOException e) {
            throw new MojoExecutionException(String.format("Failed to generate files list in '%s'", vfs.toString()), e);
        }
    }

    private void manageVenv() throws MojoExecutionException {
        generateLaunchers();

        var venvDirectory = getVenvDirectory(project);

        if (packages == null || packages.isEmpty()) {
            getLog().info(String.format("No venv packages declared, deleting %s", venvDirectory));
            delete(venvDirectory);
            return;
        }

        var tag = venvDirectory.resolve("contents");
        List installedPackages = new ArrayList<String>();
        var graalPyVersion = getGraalPyVersion(project);

        if (Files.isReadable(tag)) {
            List<String> lines = null;
            try {
                lines = Files.readAllLines(tag);
            } catch (IOException e) {
                throw new MojoExecutionException(String.format("failed to read tag file %s", tag), e);
            }
            if (lines.isEmpty() || !graalPyVersion.equals(lines.get(0))) {
                getLog().info(String.format("Stale GraalPy venv, updating to %s", graalPyVersion));
                delete(venvDirectory);
            } else {
                for (int i = 1; i < lines.size(); i++) {
                    installedPackages.add(lines.get(i));
                }
            }
        } else {
            getLog().info(String.format("Creating GraalPy %s venv", graalPyVersion));
        }

        if (!Files.exists(venvDirectory)) {
            runLauncher(getLauncherPath().toString(),"-m", "venv", venvDirectory.toString(), "--without-pip");
            runVenvBin(venvDirectory, "graalpy", "-I", "-m", "ensurepip");
        }

        deleteUnwantedPackages(venvDirectory, installedPackages);
        installWantedPackages(venvDirectory, installedPackages);

        try {
            Files.write(tag, List.of(graalPyVersion), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.write(tag, packages, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new MojoExecutionException(String.format("failed to write tag file %s", tag), e);
        }
    }

    static Path getVenvDirectory(MavenProject project) {
        return Path.of(project.getBuild().getOutputDirectory(), "vfs", "venv");
    }

    private void installWantedPackages(Path venvDirectory, List<String> installedPackages) throws MojoExecutionException {
        var pkgsToInstall = new HashSet<String>(packages);
        pkgsToInstall.removeAll(installedPackages);
        if (pkgsToInstall.isEmpty()) {
            return;
        }
        runPip(venvDirectory, "install", pkgsToInstall.toArray(new String[pkgsToInstall.size()]));
    }

    private void deleteUnwantedPackages(Path venvDirectory, List<String> installedPackages) throws MojoExecutionException {
        List<String> args = new ArrayList<String>(installedPackages);
        args.removeAll(packages);
        if (args.isEmpty()) {
            return;
        }
        args.add(0, "-y");
        runPip(venvDirectory, "uninstall", args.toArray(new String[args.size()]));
    }

    private Path getLauncherPath() {
        return Paths.get(project.getBuild().getDirectory(), LAUNCHER);
    }

    private void generateLaunchers() throws MojoExecutionException {
        getLog().info("Generating GraalPy launchers");
        var launcher = getLauncherPath();
        if (!Files.exists(launcher)) {
            var java = Paths.get(System.getProperty("java.home"), "bin", "java");
            var classpath = calculateClasspath(project);
            if (!IS_WINDOWS) {
                var script = String.format("""
                                #!/usr/bin/env bash
                                %s -classpath %s %s --python.Executable="$0" "$@"
                                """,
                        java,
                        String.join(File.pathSeparator, classpath),
                        GRAALPY_MAIN_CLASS);
                try {
                    Files.createDirectories(launcher.getParent());
                    Files.writeString(launcher, script);
                    var perms = Files.getPosixFilePermissions(launcher);
                    perms.addAll(List.of(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE));
                    Files.setPosixFilePermissions(launcher, perms);
                } catch (IOException e) {
                    throw new MojoExecutionException(String.format("failed to create launcher %s", launcher), e);
                }
            } else {
                // on windows, generate a venv launcher that executes our mvn target
                var script = String.format("""
                                import os, shutil, struct, venv
                                from pathlib import Path
                                vl = os.path.join(venv.__path__[0], 'scripts', 'nt', 'graalpy.exe')
                                tl = os.path.join(r'%s')
                                os.makedirs(Path(tl).parent.absolute(), exist_ok=True)
                                shutil.copy(vl, tl)
                                cmd = r'%s -classpath "%s" %s'
                                pyvenvcfg = os.path.join(os.path.dirname(tl), "pyvenv.cfg")
                                with open(pyvenvcfg, 'w', encoding='utf-8') as f:
                                    f.write('venvlauncher_command = ')
                                    f.write(cmd)
                                """,
                                launcher,
                                java,
                                String.join(File.pathSeparator, classpath),
                                GRAALPY_MAIN_CLASS);
                File tmp;
                try {
                    tmp = File.createTempFile("create_launcher", ".py");
                } catch (IOException e) {
                    throw new MojoExecutionException("failed to create tmp launcher", e);
                }
                tmp.deleteOnExit();
                try (var wr = new FileWriter(tmp)) {
                    wr.write(script);
                } catch (IOException e) {
                    throw new MojoExecutionException(String.format("failed to write tmp launcher %s", tmp), e);
                }
                runGraalPy(project, getLog(), tmp.getAbsolutePath());
            }
        }
    }

    private void runLauncher(String launcherPath, String... args) throws MojoExecutionException {
        try {
            GraalPyRunner.runLauncher(launcherPath, new MavenDelegateLog(getLog()), args);
        } catch(IOException | InterruptedException e) {
            throw new MojoExecutionException(String.format("failed to execute launcher command %s", List.of(args)));
        }
    }

    private void runPip(Path venvDirectory, String command, String... args) throws MojoExecutionException {
        try {
            GraalPyRunner.runPip(venvDirectory, command, new MavenDelegateLog(getLog()), args);
        } catch(IOException | InterruptedException e) {
            throw new MojoExecutionException(String.format("failed to execute pip", args), e);
        }
    }

    private void runVenvBin(Path venvDirectory, String bin, String... args) throws MojoExecutionException {
        try {
            GraalPyRunner.runVenvBin(venvDirectory, bin, new MavenDelegateLog(getLog()), args);
        } catch(IOException | InterruptedException e) {
            throw new MojoExecutionException(String.format("failed to execute venv", args), e);
        }
    }

    private static void runGraalPy(MavenProject project, Log log, String... args) throws MojoExecutionException {
        var classpath = calculateClasspath(project);
        try {
            GraalPyRunner.run(classpath, new MavenDelegateLog(log), args);
        } catch (IOException | InterruptedException e) {
            throw new MojoExecutionException(String.format("failed to run Graalpy launcher"), e);
        }
    }

    private static String getGraalPyVersion(MavenProject project) throws MojoExecutionException {
        return getGraalPyArtifact(project, PYTHON_LANGUAGE).getVersion();
    }

    private static Artifact getGraalPyArtifact(MavenProject project, String aid) throws MojoExecutionException {
        var projectArtifacts = resolveProjectDependencies(project);
        for (var a : projectArtifacts) {
            if (a.getGroupId().equals(GRAALPY_GROUP) && a.getArtifactId().equals(aid)) {
                return a;
            }
        }
        throw new MojoExecutionException(String.format("Missing GraalPy dependency %s:%s. Please add it to your pom", GRAALPY_GROUP, aid));
    }

    private static Collection<Artifact> resolveProjectDependencies(MavenProject project) {
        return project.getArtifacts()
                .stream()
                .filter(a -> !"test".equals(a.getScope()))
                .collect(Collectors.toList());
    }

    private static HashSet<String> calculateClasspath(MavenProject project) throws MojoExecutionException {
        var classpath = new HashSet<String>();
        for (var r : resolveProjectDependencies(project)) {
            classpath.add(r.getFile().getAbsolutePath());
        }
        return classpath;
    }
}
