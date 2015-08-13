package net.bytebuddy.dynamic.loading;

import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.utility.RandomString;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.security.*;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * A class injector is capable of injecting classes into a {@link java.lang.ClassLoader} without
 * requiring the class loader to being able to explicitly look up these classes.
 */
public interface ClassInjector {

    /**
     * A convenience reference to the default protection domain which is {@code null}.
     */
    ProtectionDomain DEFAULT_PROTECTION_DOMAIN = null;

    /**
     * Injects the given types into the represented class loader.
     *
     * @param types The types to load via injection.
     * @return The loaded types that were passed as arguments.
     */
    Map<TypeDescription, Class<?>> inject(Map<? extends TypeDescription, byte[]> types);

    /**
     * A class injector that uses reflective method calls.
     */
    class UsingReflection implements ClassInjector {

        /**
         * A storage for the reflection method representations that are obtained on loading this classes.
         */
        private static final ReflectionStore REFLECTION_STORE;

        /*
         * Obtains the reflective instances used by this injector or a no-op instance that throws the exception
         * that occurred when attempting to obtain the reflective member instances.
         */
        static {
            ReflectionStore reflectionStore;
            try {
                Method findLoadedClass = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
                findLoadedClass.setAccessible(true);
                Method defineClass = ClassLoader.class.getDeclaredMethod("defineClass",
                        String.class,
                        byte[].class,
                        int.class,
                        int.class,
                        ProtectionDomain.class);
                defineClass.setAccessible(true);
                Method getPackage = ClassLoader.class.getDeclaredMethod("getPackage", String.class);
                getPackage.setAccessible(true);
                Method definePackage = ClassLoader.class.getDeclaredMethod("definePackage",
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        URL.class);
                definePackage.setAccessible(true);
                reflectionStore = new ReflectionStore.Resolved(findLoadedClass, defineClass, getPackage, definePackage);
            } catch (Exception exception) {
                reflectionStore = new ReflectionStore.Faulty(exception);
            }
            REFLECTION_STORE = reflectionStore;
        }

        /**
         * The class loader into which the classes are to be injected.
         */
        private final ClassLoader classLoader;

        /**
         * The protection domain that is used when loading classes.
         */
        private final ProtectionDomain protectionDomain;

        /**
         * The access control context of this class loader's instantiation.
         */
        private final AccessControlContext accessControlContext;

        /**
         * The package definer to be queried for package definitions.
         */
        private final PackageDefinitionStrategy packageDefinitionStrategy;

        /**
         * Creates a new injector for the given {@link java.lang.ClassLoader} and a default {@link java.security.ProtectionDomain}
         * and {@link PackageDefinitionStrategy}.
         *
         * @param classLoader The {@link java.lang.ClassLoader} into which new class definitions are to be injected.
         */
        public UsingReflection(ClassLoader classLoader) {
            this(classLoader,
                    DEFAULT_PROTECTION_DOMAIN,
                    AccessController.getContext(),
                    PackageDefinitionStrategy.Trivial.INSTANCE);
        }

        /**
         * Creates a new injector for the given {@link java.lang.ClassLoader} and {@link java.security.ProtectionDomain}.
         *
         * @param classLoader               The {@link java.lang.ClassLoader} into which new class definitions are to be injected.
         * @param packageDefinitionStrategy The package definer to be queried for package definitions.
         * @param accessControlContext      The access control context of this class loader's instantiation.
         * @param protectionDomain          The protection domain to apply during class definition.
         */
        public UsingReflection(ClassLoader classLoader,
                               ProtectionDomain protectionDomain,
                               AccessControlContext accessControlContext,
                               PackageDefinitionStrategy packageDefinitionStrategy) {
            if (classLoader == null) {
                throw new IllegalArgumentException("Cannot inject classes into the bootstrap class loader");
            }
            this.classLoader = classLoader;
            this.protectionDomain = protectionDomain;
            this.packageDefinitionStrategy = packageDefinitionStrategy;
            this.accessControlContext = accessControlContext;
        }

        @Override
        public Map<TypeDescription, Class<?>> inject(Map<? extends TypeDescription, byte[]> types) {
            try {
                synchronized (classLoader) {
                    return AccessController.doPrivileged(new ClassInjectionAction(types), accessControlContext);
                }
            } catch (PrivilegedActionException exception) {
                throw new IllegalStateException("Could not access injection method", exception.getException() instanceof InvocationTargetException
                        ? exception.getException().getCause()
                        : exception.getException());
            }
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (other == null || getClass() != other.getClass()) return false;
            UsingReflection that = (UsingReflection) other;
            return accessControlContext.equals(that.accessControlContext)
                    && classLoader.equals(that.classLoader)
                    && packageDefinitionStrategy.equals(that.packageDefinitionStrategy)
                    && !(protectionDomain != null ? !protectionDomain.equals(that.protectionDomain) : that.protectionDomain != null);
        }

        @Override
        public int hashCode() {
            int result = classLoader.hashCode();
            result = 31 * result + (protectionDomain != null ? protectionDomain.hashCode() : 0);
            result = 31 * result + packageDefinitionStrategy.hashCode();
            result = 31 * result + accessControlContext.hashCode();
            return result;
        }

        @Override
        public String toString() {
            return "ClassInjector.UsingReflection{" +
                    "classLoader=" + classLoader +
                    ", protectionDomain=" + protectionDomain +
                    ", packageDefinitionStrategy=" + packageDefinitionStrategy +
                    ", accessControlContext=" + accessControlContext +
                    '}';
        }

        /**
         * A storage for method representations in order to access a class loader reflectively.
         */
        protected interface ReflectionStore {

            /**
             * Looks up a class from the given class loader.
             *
             * @param classLoader The class loader for which a class should be located.
             * @param name        The binary name of the class that should be located.
             * @return The class for the binary name or {@code null} if no such class is defined for the provided class loader.
             * @throws InvocationTargetException If an exception is caused during invocation.
             * @throws IllegalAccessException    If the access was refused.
             */
            Class<?> findClass(ClassLoader classLoader, String name) throws IllegalAccessException, InvocationTargetException;

            /**
             * Defines a class for the given class loader.
             *
             * @param classLoader          The class loader for which a new class should be defined.
             * @param name                 The binary name of the class that should be defined.
             * @param binaryRepresentation The binary representation of the class.
             * @param startIndex           The start index of the provided binary representation.
             * @param endIndex             The final index of the binary representation.
             * @param protectionDomain     The protection domain for the defined class.
             * @return The defined, loaded class.
             * @throws InvocationTargetException If an exception is caused during invocation.
             * @throws IllegalAccessException    If the access was refused.
             */
            Class<?> loadClass(ClassLoader classLoader,
                               String name,
                               byte[] binaryRepresentation,
                               int startIndex,
                               int endIndex,
                               ProtectionDomain protectionDomain) throws InvocationTargetException, IllegalAccessException;

            /**
             * Looks up a package from a class loader.
             *
             * @param classLoader The class loader to query.
             * @param name        The binary name of the package.
             * @return The package for the given name as defined by the provided class loader or {@code null} if no such package exists.
             * @throws InvocationTargetException If an exception is caused during invocation.
             * @throws IllegalAccessException    If the access was refused.
             */
            Package getPackage(ClassLoader classLoader, String name) throws InvocationTargetException, IllegalAccessException;

            /**
             * Defines a package for the given class loader.
             *
             * @param classLoader           The class loader for which a package is to be defined.
             * @param packageName           The binary name of the package.
             * @param specificationTitle    The specification title of the package or {@code null} if no specification title exists.
             * @param specificationVersion  The specification version of the package or {@code null} if no specification version exists.
             * @param specificationVendor   The specification vendor of the package or {@code null} if no specification vendor exists.
             * @param implementationTitle   The implementation title of the package or {@code null} if no implementation title exists.
             * @param implementationVersion The implementation version of the package or {@code null} if no implementation version exists.
             * @param implementationVendor  The implementation vendor of the package or {@code null} if no implementation vendor exists.
             * @param sealBase              The seal base URL or {@code null} if the package should not be sealed.
             * @return The defined package.
             * @throws InvocationTargetException If an exception is caused during invocation.
             * @throws IllegalAccessException    If the access was refused.
             */
            Package definePackage(ClassLoader classLoader,
                                  String packageName,
                                  String specificationTitle,
                                  String specificationVersion,
                                  String specificationVendor,
                                  String implementationTitle,
                                  String implementationVersion,
                                  String implementationVendor,
                                  URL sealBase) throws InvocationTargetException, IllegalAccessException;

            /**
             * Represents a successfully loaded method lookup.
             */
            class Resolved implements ReflectionStore {

                /**
                 * An accessible instance of {@link ClassLoader#findLoadedClass(String)}.
                 */
                private final Method findLoadedClass;

                /**
                 * An accessible instance of {@link ClassLoader#loadClass(String)}.
                 */
                private final Method loadClass;

                /**
                 * An accessible instance of {@link ClassLoader#getPackage(String)}.
                 */
                private final Method getPackage;

                /**
                 * An accessible instance of {@link ClassLoader#definePackage(String, String, String, String, String, String, String, URL)}.
                 */
                private final Method definePackage;

                /**
                 * Creates a new resolved reflection store.
                 *
                 * @param findLoadedClass An accessible instance of {@link ClassLoader#findLoadedClass(String)}.
                 * @param loadClass       An accessible instance of {@link ClassLoader#loadClass(String)}.
                 * @param getPackage      An accessible instance of {@link ClassLoader#getPackage(String)}.
                 * @param definePackage   An accessible instance of
                 *                        {@link ClassLoader#definePackage(String, String, String, String, String, String, String, URL)}.
                 */
                protected Resolved(Method findLoadedClass, Method loadClass, Method getPackage, Method definePackage) {
                    this.findLoadedClass = findLoadedClass;
                    this.loadClass = loadClass;
                    this.getPackage = getPackage;
                    this.definePackage = definePackage;
                }

                @Override
                public Class<?> findClass(ClassLoader classLoader, String name) throws IllegalAccessException, InvocationTargetException {
                    return (Class<?>) findLoadedClass.invoke(classLoader, name);
                }

                @Override
                public Class<?> loadClass(ClassLoader classLoader,
                                          String name,
                                          byte[] binaryRepresentation,
                                          int startIndex,
                                          int endIndex,
                                          ProtectionDomain protectionDomain) throws InvocationTargetException, IllegalAccessException {
                    return (Class<?>) loadClass.invoke(classLoader, name, binaryRepresentation, startIndex, endIndex, protectionDomain);
                }

                @Override
                public Package getPackage(ClassLoader classLoader, String name) throws InvocationTargetException, IllegalAccessException {
                    return (Package) getPackage.invoke(classLoader, name);
                }

                @Override
                public Package definePackage(ClassLoader classLoader,
                                             String packageName,
                                             String specificationTitle,
                                             String specificationVersion,
                                             String specificationVendor,
                                             String implementationTitle,
                                             String implementationVersion,
                                             String implementationVendor,
                                             URL sealBase) throws InvocationTargetException, IllegalAccessException {
                    return (Package) definePackage.invoke(classLoader,
                            packageName,
                            specificationTitle,
                            specificationVersion,
                            specificationVendor,
                            implementationTitle,
                            implementationVersion,
                            implementationVendor,
                            sealBase);
                }

                @Override
                public boolean equals(Object other) {
                    if (this == other) return true;
                    if (other == null || getClass() != other.getClass()) return false;
                    Resolved resolved = (Resolved) other;
                    return findLoadedClass.equals(resolved.findLoadedClass)
                            && loadClass.equals(resolved.loadClass)
                            && getPackage.equals(resolved.getPackage)
                            && definePackage.equals(resolved.definePackage);
                }

                @Override
                public int hashCode() {
                    int result = findLoadedClass.hashCode();
                    result = 31 * result + loadClass.hashCode();
                    result = 31 * result + getPackage.hashCode();
                    result = 31 * result + definePackage.hashCode();
                    return result;
                }

                @Override
                public String toString() {
                    return "ClassInjector.UsingReflection.ReflectionStore.Resolved{" +
                            "findLoadedClass=" + findLoadedClass +
                            ", loadClass=" + loadClass +
                            ", getPackage=" + getPackage +
                            ", definePackage=" + definePackage +
                            '}';
                }
            }

            /**
             * Represents an unsuccessfully loaded method lookup.
             */
            class Faulty implements ReflectionStore {

                /**
                 * The message to display in an exception.
                 */
                private static final String MESSAGE = "Cannot access reflection API for class loading";

                /**
                 * The exception that occurred when looking up the reflection methods.
                 */
                private final Exception exception;

                /**
                 * Creates a new faulty reflection store.
                 *
                 * @param exception The exception that was thrown when attempting to lookup the method.
                 */
                protected Faulty(Exception exception) {
                    this.exception = exception;
                }

                @Override
                public Class<?> findClass(ClassLoader classLoader, String name) {
                    throw new IllegalStateException(MESSAGE, exception);
                }

                @Override
                public Class<?> loadClass(ClassLoader classLoader,
                                          String name,
                                          byte[] binaryRepresentation,
                                          int startIndex,
                                          int endIndex,
                                          ProtectionDomain protectionDomain) {
                    throw new IllegalStateException(MESSAGE, exception);
                }


                @Override
                public Package getPackage(ClassLoader classLoader, String name) {
                    throw new IllegalStateException(MESSAGE, exception);
                }

                @Override
                public Package definePackage(ClassLoader classLoader,
                                             String packageName,
                                             String specificationTitle,
                                             String specificationVersion,
                                             String specificationVendor,
                                             String implementationTitle,
                                             String implementationVersion,
                                             String implementationVendor,
                                             URL sealBase) {
                    throw new IllegalStateException(MESSAGE, exception);
                }

                @Override
                public boolean equals(Object other) {
                    return this == other || !(other == null || getClass() != other.getClass())
                            && exception.equals(((Faulty) other).exception);
                }

                @Override
                public int hashCode() {
                    return exception.hashCode();
                }

                @Override
                public String toString() {
                    return "ClassInjector.UsingReflection.ReflectionStore.Faulty{exception=" + exception + '}';
                }
            }
        }

        /**
         * A privileged action for loading a class reflectively.
         */
        protected class ClassInjectionAction implements PrivilegedExceptionAction<Map<TypeDescription, Class<?>>> {

            /**
             * A convenience variable representing the first index of an array, to make the code more readable.
             */
            private static final int FROM_BEGINNING = 0;

            /**
             * The types to be loaded mapping to their binary representation.
             */
            private final Map<? extends TypeDescription, byte[]> types;

            /**
             * Creates a new class injection action.
             *
             * @param types The types to be loaded mapping to their binary representation.
             */
            protected ClassInjectionAction(Map<? extends TypeDescription, byte[]> types) {
                this.types = types;
            }

            @Override
            public Map<TypeDescription, Class<?>> run() throws IllegalAccessException, InvocationTargetException, IOException {
                Map<TypeDescription, Class<?>> loadedTypes = new HashMap<TypeDescription, Class<?>>(types.size());
                for (Map.Entry<? extends TypeDescription, byte[]> entry : types.entrySet()) {
                    String typeName = entry.getKey().getName();
                    Class<?> type = REFLECTION_STORE.findClass(classLoader, typeName);
                    if (type == null) {
                        int packageIndex = typeName.lastIndexOf('.');
                        if (packageIndex != -1) {
                            String packageName = typeName.substring(0, packageIndex);
                            PackageDefinitionStrategy.Definition definition = packageDefinitionStrategy.define(classLoader, packageName, typeName);
                            if (definition.isDefined()) {
                                Package definedPackage = REFLECTION_STORE.getPackage(classLoader, packageName);
                                if (definedPackage == null) {
                                    REFLECTION_STORE.definePackage(classLoader,
                                            packageName,
                                            definition.getSpecificationTitle(),
                                            definition.getSpecificationVersion(),
                                            definition.getSpecificationVendor(),
                                            definition.getImplementationTitle(),
                                            definition.getImplementationVersion(),
                                            definition.getImplementationVendor(),
                                            definition.getSealBase());
                                } else if (!definition.isCompatibleTo(definedPackage)) {
                                    throw new SecurityException("Sealing violation for package " + packageName);
                                }
                            }
                        }
                        byte[] binaryRepresentation = entry.getValue();
                        type = REFLECTION_STORE.loadClass(classLoader,
                                typeName,
                                binaryRepresentation,
                                FROM_BEGINNING,
                                binaryRepresentation.length,
                                protectionDomain);
                    }
                    loadedTypes.put(entry.getKey(), type);
                }
                return loadedTypes;
            }

            /**
             * Returns the outer instance.
             *
             * @return The outer instance.
             */
            private UsingReflection getOuter() {
                return UsingReflection.this;
            }

            @Override
            public boolean equals(Object other) {
                return this == other || !(other == null || getClass() != other.getClass())
                        && UsingReflection.this.equals(((ClassInjectionAction) other).getOuter())
                        && types.equals(((ClassInjectionAction) other).types);
            }

            @Override
            public int hashCode() {
                return types.hashCode() + 31 * UsingReflection.this.hashCode();
            }

            @Override
            public String toString() {
                return "ClassInjector.UsingReflection.ClassInjectionAction{" +
                        "usingReflection=" + UsingReflection.this +
                        ",types=" + types +
                        '}';
            }
        }
    }

    /**
     * A class injector using a {@link java.lang.instrument.Instrumentation} to append to either the boot classpath
     * or the system class path.
     */
    class UsingInstrumentation implements ClassInjector {

        /**
         * A prefix to use of generated files.
         */
        private static final String PREFIX = "jar";

        /**
         * The class file extension.
         */
        private static final String CLASS_FILE_EXTENSION = ".class";

        /**
         * The instrumentation to use for appending to the class path or the boot path.
         */
        private final Instrumentation instrumentation;

        /**
         * A representation of the target path to which classes are to be appended.
         */
        private final Target target;

        /**
         * The folder to be used for storing jar files.
         */
        private final File folder;

        /**
         * A random string generator for creating file names.
         */
        private final RandomString randomString;

        /**
         * Creates an instrumentation-based class injector.
         *
         * @param folder          The folder to be used for storing jar files.
         * @param target          A representation of the target path to which classes are to be appended.
         * @param instrumentation The instrumentation to use for appending to the class path or the boot path.
         */
        public UsingInstrumentation(File folder, Target target, Instrumentation instrumentation) {
            this.folder = folder;
            this.target = target;
            this.instrumentation = instrumentation;
            randomString = new RandomString();
        }

        @Override
        public Map<TypeDescription, Class<?>> inject(Map<? extends TypeDescription, byte[]> types) {
            File jarFile = new File(folder, String.format("%s%s.jar", PREFIX, randomString.nextString()));
            try {
                if (!jarFile.createNewFile()) {
                    throw new IllegalStateException("Cannot create file " + jarFile);
                }
                JarOutputStream jarOutputStream = new JarOutputStream(new BufferedOutputStream(new FileOutputStream(jarFile)));
                try {
                    for (Map.Entry<? extends TypeDescription, byte[]> entry : types.entrySet()) {
                        jarOutputStream.putNextEntry(new JarEntry(entry.getKey().getInternalName() + CLASS_FILE_EXTENSION));
                        jarOutputStream.write(entry.getValue());
                    }
                } finally {
                    jarOutputStream.close();
                }
                target.inject(instrumentation, new JarFile(jarFile));
                Map<TypeDescription, Class<?>> loaded = new HashMap<TypeDescription, Class<?>>(types.size());
                ClassLoader classLoader = ClassLoader.getSystemClassLoader();
                for (TypeDescription typeDescription : types.keySet()) {
                    loaded.put(typeDescription, classLoader.loadClass(typeDescription.getName()));
                }
                return loaded;
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot write jar file to disk", exception);
            } catch (ClassNotFoundException exception) {
                throw new IllegalStateException("Cannot load injected class", exception);
            }
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (other == null || getClass() != other.getClass()) return false;
            UsingInstrumentation that = (UsingInstrumentation) other;
            return folder.equals(that.folder)
                    && instrumentation.equals(that.instrumentation)
                    && target == that.target;
        }

        @Override
        public int hashCode() {
            int result = instrumentation.hashCode();
            result = 31 * result + target.hashCode();
            result = 31 * result + folder.hashCode();
            return result;
        }

        @Override
        public String toString() {
            return "ClassInjector.UsingInstrumentation{" +
                    "instrumentation=" + instrumentation +
                    ", target=" + target +
                    ", folder=" + folder +
                    ", randomString=" + randomString +
                    '}';
        }

        /**
         * A representation of the target to which Java classes should be appended to.
         */
        public enum Target {

            /**
             * Representation of the bootstrap class loader.
             */
            BOOTSTRAP {
                @Override
                protected void inject(Instrumentation instrumentation, JarFile jarFile) {
                    instrumentation.appendToBootstrapClassLoaderSearch(jarFile);
                }
            },

            /**
             * Representation of the system class loader.
             */
            SYSTEM {
                @Override
                protected void inject(Instrumentation instrumentation, JarFile jarFile) {
                    instrumentation.appendToSystemClassLoaderSearch(jarFile);
                }
            };

            /**
             * Adds the given classes to the represented class loader.
             *
             * @param instrumentation The instrumentation instance to use.
             * @param jarFile         The jar file to append.
             */
            protected abstract void inject(Instrumentation instrumentation, JarFile jarFile);

            @Override
            public String toString() {
                return "ClassInjector.UsingInstrumentation.Target." + name();
            }
        }
    }
}
