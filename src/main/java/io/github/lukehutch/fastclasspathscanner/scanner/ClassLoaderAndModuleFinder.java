/*
 * This file is part of FastClasspathScanner.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/lukehutch/fast-classpath-scanner
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.lukehutch.fastclasspathscanner.scanner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.lukehutch.fastclasspathscanner.utils.AdditionOrderedSet;
import io.github.lukehutch.fastclasspathscanner.utils.LogNode;

/** A class to find the unique ordered classpath elements. */
public class ClassLoaderAndModuleFinder {
    private final ClassLoader[] classLoaders;
    private final List<ModuleRef> systemModuleRefs;
    private final List<ModuleRef> nonSystemModuleRefs;

    /** Get context classloader, and any other classloader that is not an ancestor of context classloader. */
    public ClassLoader[] getClassLoaders() {
        return classLoaders;
    }

    /** Get system modules as ModuleRef wrappers, or null if no modules were found (e.g. on JDK 7 or 8). */
    public List<ModuleRef> getSystemModuleRefs() {
        return systemModuleRefs;
    }

    /** Get non-system modules as ModuleRef wrappers, or null if no modules were found (e.g. on JDK 7 or 8). */
    public List<ModuleRef> getNonSystemModuleRefs() {
        return nonSystemModuleRefs;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Used for resolving the classes in the call stack. Requires RuntimePermission("createSecurityManager").
     */
    private static CallerResolver CALLER_RESOLVER;

    static {
        try {
            // This can fail if the current SecurityManager does not allow RuntimePermission
            // ("createSecurityManager"):
            CALLER_RESOLVER = new CallerResolver();
        } catch (final SecurityException e) {
        }
    }

    /**
     * Using a SecurityManager gets around the fact that Oracle removed sun.reflect.Reflection.getCallerClass, see:
     * 
     * https://www.infoq.com/news/2013/07/Oracle-Removes-getCallerClass
     *
     * http://www.javaworld.com/article/2077344/core-java/find-a-way-out-of-the-classloader-maze.html
     */
    private static final class CallerResolver extends SecurityManager {
        @Override
        protected Class<?>[] getClassContext() {
            return super.getClassContext();
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * A class to find the unique ordered classpath elements.
     * 
     * @param scanSpec
     *            The scan spec, or null if none available.
     * @param log
     *            The log.
     * @return The list of classloaders for this environment, and any system and non-system modules that are
     *         discovered (in JDK9+).
     */
    public ClassLoaderAndModuleFinder(final ScanSpec scanSpec, final LogNode log) {
        AdditionOrderedSet<ClassLoader> classLoadersUnique;
        LogNode classLoadersFoundLog = null;
        List<ModuleRef> systemModuleRefs = null;
        List<ModuleRef> nonSystemModuleRefs = null;
        if (scanSpec == null || scanSpec.overrideClassLoaders == null) {
            // ClassLoaders were not overridden

            // Add the ClassLoaders in the order system, caller, context; then remove any of them that are
            // parents/ancestors of one or more other classloaders (performed below). There will generally only be
            // one class left after this. In rare cases, you may have a separate callerLoader and contextLoader, but
            // those cases are ill-defined -- see:
            // http://www.javaworld.com/article/2077344/core-java/find-a-way-out-of-the-classloader-maze.html?page=2

            // Get system classloader
            classLoadersUnique = new AdditionOrderedSet<>();
            final ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
            if (systemClassLoader != null) {
                classLoadersUnique.add(systemClassLoader);
            }

            // There is one more classloader in JDK9+, the platform classloader (used for handling extensions),
            // see: http://openjdk.java.net/jeps/261#Class-loaders
            // The method call to get it is ClassLoader.getPlatformClassLoader()
            // However, since it's not possible to get URLs from this classloader, and it is the parent of
            // the application classloader returned by ClassLoader.getSystemClassLoader() (so is delegated to
            // by the application classloader), there is no point adding it here.

            // Get caller classloader
            if (CALLER_RESOLVER == null) {
                if (log != null) {
                    log.log(ClassLoaderAndModuleFinder.class.getSimpleName() + " could not create "
                            + CallerResolver.class.getSimpleName() + ", current SecurityManager does not grant "
                            + "RuntimePermission(\"createSecurityManager\")");
                }
            } else {
                final Class<?>[] callStack = CALLER_RESOLVER.getClassContext();
                if (callStack == null) {
                    if (log != null) {
                        log.log(ClassLoaderAndModuleFinder.class.getSimpleName() + ": "
                                + CallerResolver.class.getSimpleName() + "#getClassContext() returned null");
                    }
                } else {
                    // Find classloaders for classes on callstack
                    for (int i = callStack.length - 1; i >= 0; --i) {
                        final ClassLoader callerClassLoader = callStack[i].getClassLoader();
                        if (callerClassLoader != null) {
                            classLoadersUnique.add(callerClassLoader);
                        }
                    }
                    // Find module references for classes on callstack (for JDK9+)
                    final List<ModuleRef> allModuleRefsList = ModuleRef.findModuleRefs(callStack);

                    // Split modules into system modules and non-system modules
                    systemModuleRefs = new ArrayList<>();
                    nonSystemModuleRefs = new ArrayList<>();
                    for (final ModuleRef moduleRef : allModuleRefsList) {
                        if (moduleRef.isSystemModule()) {
                            systemModuleRefs.add(moduleRef);
                        } else {
                            nonSystemModuleRefs.add(moduleRef);
                        }
                    }
                }
            }

            // Get context classloader
            final ClassLoader threadClassLoader = Thread.currentThread().getContextClassLoader();
            if (threadClassLoader != null) {
                classLoadersUnique.add(threadClassLoader);
            }

            // Add any custom-added classloaders after system/context classloaders
            if (scanSpec != null && scanSpec.addedClassLoaders != null) {
                classLoadersUnique.addAll(scanSpec.addedClassLoaders);
            }
            classLoadersFoundLog = log == null ? null : log.log("Found ClassLoaders:");

        } else {
            // ClassLoaders were overridden
            classLoadersUnique = new AdditionOrderedSet<>(scanSpec.overrideClassLoaders);
            classLoadersFoundLog = log == null ? null : log.log("Override ClassLoaders:");
        }

        // Remove all ancestral classloaders (they are called automatically during class load)
        final Set<ClassLoader> ancestralClassLoaders = new HashSet<>(classLoadersUnique.size());
        for (final ClassLoader classLoader : classLoadersUnique) {
            for (ClassLoader cl = classLoader.getParent(); cl != null; cl = cl.getParent()) {
                ancestralClassLoaders.add(cl);
            }
        }
        final List<ClassLoader> classLoaderFinalOrder = new ArrayList<>(classLoadersUnique.size());
        for (final ClassLoader classLoader : classLoadersUnique) {
            // Build final ClassLoader order, with ancestral classloaders removed
            if (!ancestralClassLoaders.contains(classLoader)) {
                classLoaderFinalOrder.add(classLoader);
            }
        }

        // Log all identified ClassLoaders
        if (classLoadersFoundLog != null) {
            for (final ClassLoader cl : classLoaderFinalOrder) {
                classLoadersFoundLog.log("" + cl);
            }
        }

        // Log any identified modules
        if (log != null) {
            final LogNode sysSubLog = log.log("Found system modules:");
            if (systemModuleRefs != null && !systemModuleRefs.isEmpty()) {
                for (final ModuleRef moduleRef : systemModuleRefs) {
                    sysSubLog.log(moduleRef.toString());
                }
            } else {
                sysSubLog.log("[None]");
            }
            final LogNode nonSysSubLog = log.log("Found non-system modules:");
            if (nonSystemModuleRefs != null && !nonSystemModuleRefs.isEmpty()) {
                for (final ModuleRef moduleRef : nonSystemModuleRefs) {
                    nonSysSubLog.log(moduleRef.toString());
                }
            } else {
                nonSysSubLog.log("[None]");
            }
        }

        this.classLoaders = classLoaderFinalOrder.toArray(new ClassLoader[classLoaderFinalOrder.size()]);
        this.systemModuleRefs = systemModuleRefs;
        this.nonSystemModuleRefs = nonSystemModuleRefs;
    }
}
