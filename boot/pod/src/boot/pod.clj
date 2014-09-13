(ns boot.pod
  (:require
   [boot.util                  :as util]
   [boot.from.backtick         :as bt]
   [clojure.java.io            :as io]
   [dynapath.util              :as dp]
   [dynapath.dynamic-classpath :as cp])
  (:import
   [java.util.jar JarFile]
   [java.util     Properties]
   [java.net      URL URLClassLoader]
   [clojure.lang  DynamicClassLoader])
  (:refer-clojure :exclude [add-classpath]))


(defn extract-ids
  [sym]
  (let [[group artifact] ((juxt namespace name) sym)]
    [(or group artifact) artifact]))

(defn seal-app-classloader
  "see https://github.com/clojure-emacs/cider-nrepl#with-immutant"
  []
  (extend sun.misc.Launcher$AppClassLoader
    cp/DynamicClasspath
    (assoc cp/base-readable-addable-classpath
      :classpath-urls #(seq (.getURLs %))
      :can-add? (constantly false))))

(defn ^{:boot/from :cemerick/pomegranate} classloader-hierarchy
  "Returns a seq of classloaders, with the tip of the hierarchy first.
   Uses the current thread context ClassLoader as the tip ClassLoader
   if one is not provided."
  ([] (classloader-hierarchy (.. Thread currentThread getContextClassLoader)))
  ([tip] (->> tip (iterate #(.getParent %)) (take-while boolean))))

(defn ^{:boot/from :cemerick/pomegranate} modifiable-classloader?
  "Returns true iff the given ClassLoader is of a type that satisfies
   the dynapath.dynamic-classpath/DynamicClasspath protocol, and it can
   be modified."
  [cl]
  (dp/addable-classpath? cl))

(defn ^{:boot/from :cemerick/pomegranate} add-classpath
  "A corollary to the (deprecated) `add-classpath` in clojure.core. This implementation
   requires a java.io.File or String path to a jar file or directory, and will attempt
   to add that path to the right classloader (with the search rooted at the current
   thread's context classloader)."
  ([jar-or-dir classloader]
     (if-not (dp/add-classpath-url classloader (.toURL (.toURI (io/file jar-or-dir))))
       (throw (IllegalStateException. (str classloader " is not a modifiable classloader")))))
  ([jar-or-dir]
    (let [classloaders (classloader-hierarchy)]
      (if-let [cl (last (filter modifiable-classloader? classloaders))]
        (add-classpath jar-or-dir cl)
        (throw (IllegalStateException. (str "Could not find a suitable classloader to modify from "
                                            classloaders)))))))

(defn ^{:boot/from :cemerick/pomegranate} get-classpath
  "Returns the effective classpath (i.e. _not_ the value of
   (System/getProperty \"java.class.path\") as a seq of URL strings.

   Produces the classpath from all classloaders by default, or from a
   collection of classloaders if provided.  This allows you to easily look
   at subsets of the current classloader hierarchy, e.g.:

   (get-classpath (drop 2 (classloader-hierarchy)))"
  ([classloaders]
    (->> (reverse classloaders)
      (mapcat #(dp/classpath-urls %))
      (map str)))
  ([] (get-classpath (classloader-hierarchy))))

(defn ^{:boot/from :cemerick/pomegranate} classloader-resources
  "Returns a sequence of [classloader url-seq] pairs representing all
   of the resources of the specified name on the classpath of each
   classloader. If no classloaders are given, uses the
   classloader-heirarchy, in which case the order of pairs will be
   such that the first url mentioned will in most circumstances match
   what clojure.java.io/resource returns."
  ([classloaders resource-name]
     (for [classloader (reverse classloaders)]
       [classloader (enumeration-seq
                      (.getResources ^ClassLoader classloader resource-name))]))
  ([resource-name] (classloader-resources (classloader-hierarchy) resource-name)))

(defn ^{:boot/from :cemerick/pomegranate} resources
  "Returns a sequence of URLs representing all of the resources of the
   specified name on the effective classpath. This can be useful for
   finding name collisions among items on the classpath. In most
   circumstances, the first of the returned sequence will be the same
   as what clojure.java.io/resource returns."
  ([classloaders resource-name]
     (distinct (mapcat second (classloader-resources classloaders resource-name))))
  ([resource-name] (resources (classloader-hierarchy) resource-name)))

(defn pom-properties
  [jarpath]
  (let [jarfile (JarFile. (io/file jarpath))]
    (doto (Properties.)
      (.load (->> jarfile .entries enumeration-seq
               (filter #(.endsWith (.getName %) "/pom.properties"))
               first
               (.getInputStream jarfile))))))

(defn pom-properties-map
  [pom-properties-path]
  (let [p (doto (Properties.) (.load (io/input-stream pom-properties-path)))]
    {:project (symbol (.getProperty p "groupId") (.getProperty p "artifactId"))
     :version (.getProperty p "version")}))

(defn pom-xml
  [jarpath]
  (let [jarfile (JarFile. (io/file jarpath))]
    (->> jarfile .entries enumeration-seq
      (filter #(.endsWith (.getName %) "/pom.xml"))
      first (.getInputStream jarfile) slurp)))

(defn copy-resource
  [resource-path out-path]
  (with-open [in  (io/input-stream (io/resource resource-path))
              out (io/output-stream (doto (io/file out-path) io/make-parents))]
    (io/copy in out)))

(defn call-in
  ([expr]
     (let [[f & args] (read-string expr)]
       (when-let [ns (namespace f)] (require (symbol ns)))
       (if-let [f (resolve f)]
         (pr-str (apply f args))
         (throw (Exception. (format "can't resolve symbol (%s)" f))))))
  ([pod expr]
     (let [ret (.invoke pod "boot.pod/call-in" (pr-str expr))]
      (util/guard (read-string ret)))))

(def worker-pod (atom nil))

(defn call-worker
  [expr]
  (call-in @worker-pod expr))

(defn resolve-dependencies
  [env]
  (call-worker `(boot.aether/resolve-dependencies ~env)))

(defn resolve-dependency-jars
  [env]
  (->> env resolve-dependencies (map (comp io/file :jar))))

(defn add-dependencies
  [env]
  (doseq [jar (resolve-dependency-jars env)] (add-classpath jar)))

(defn make-pod
  ([] (boot.App/newPod))
  ([{:keys [src-paths] :as env}]
     (let [dirs (map io/file src-paths)
           jars (resolve-dependency-jars env)]
       (->> (concat dirs jars) (into-array java.io.File) (boot.App/newPod)))))
