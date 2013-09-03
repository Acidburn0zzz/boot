(ns tailrecursion.boot
  (:require [cemerick.pomegranate           :as pom]
            [clojure.java.io                :as io]
            [tailrecursion.boot.tmpregistry :as tmp])
  (:import java.lang.management.ManagementFactory
           [java.net URLClassLoader URL])
  (:gen-class))

(declare configure!)

(def base-env
  {:project       nil
   :version       nil
   :dependencies  #{}
   :directories   #{}
   :repositories  #{"http://repo1.maven.org/maven2/" "http://clojars.org/repo/"}
   :system        {:jvm-opts (vec (.. ManagementFactory getRuntimeMXBean getInputArguments))
                   :bootfile (io/file (System/getProperty "user.dir") "boot.clj")
                   :cwd      (io/file (System/getProperty "user.dir"))}
   :tmp           nil
   :main          nil
   :tasks         nil})

(defn index-of [v val]
  (ffirst (filter (comp #{val} second) (map vector (range) v))))

(defn exclude [syms coordinate]
  (if-let [idx (index-of coordinate :exclusions)]
    (let [exclusions (get coordinate (inc idx))]
      (assoc coordinate (inc idx) (into exclusions syms)))
    (into coordinate [:exclusions syms])))

(defn add-dependencies! [env]
  (let [{deps :dependencies, repos :repositories} env
        deps (mapv (partial exclude ['org.clojure/clojure]) deps)]
    (pom/add-dependencies :coordinates deps :repositories (zipmap repos repos))))

(defn add-directories! [env]
  (when-let [dirs (seq (:directories env))] 
    (let [meth (doto (.getDeclaredMethod URLClassLoader "addURL" (into-array Class [URL])) (.setAccessible true))]
      (.invoke meth (ClassLoader/getSystemClassLoader) (object-array (map #(.. (io/file %) toURI toURL) dirs))))))

(defn configure! [old new]
  (when-not (= (:dependencies old) (:dependencies new)) (add-dependencies! new))
  (when-not (= (:directories old) (:directories new)) (add-directories! new)))

(defn make-env [base-env]
  (doto (atom base-env) (add-watch (gensym) (fn [_ _ o n] (configure! o n)))))

(defn load-fn [sym]
  (when-let [ns (namespace sym)] (require (symbol ns))) 
  (or (resolve sym) (assert false (format "Can't resolve #'%s." sym))))

(defn -main [& args]
  (let [env (make-env base-env)
        f   (io/file (get-in @env [:system :bootfile]))]
    (assert (.exists f) (format "File '%s' not found." f))
    (let [spec  (read-string (slurp f))
          task  (when-let [t (first args)] (get-in spec [:tasks (keyword t)])) 
          argv  (if task (rest args) args)
          sel   #(select-keys % [:directories :dependencies :repositories])
          deps  (merge-with into (sel spec) (sel task))]
      (swap! env #(assoc-in (merge % spec task deps) [:system :argv] argv))
      (when-let [m (:main @env)]
        (swap! env assoc :tmp (tmp/init! (tmp/registry (io/file ".boot" "tmp")))) 
        (cond (symbol? m)   ((load-fn m) env)
              (seq? m)      ((eval m) env))))))
