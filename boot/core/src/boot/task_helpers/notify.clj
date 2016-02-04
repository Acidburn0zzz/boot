(ns boot.task-helpers.notify
  (:require [clojure.java.shell :refer [sh]]))

(defn- ^{:boot/from :jeluard/boot-notify} program-exists?
  [s]
  (= 0 (:exit (sh "sh" "-c" (format "command -v %s" s)))))

(defmulti notify-method
  (fn [os _message]
    os))

(defmethod notify-method "Mac OS X"
  [_ {:keys [message title icon pid] :as notification}]
  (if (program-exists? "terminal_notifier")
    (sh "terminal-notifier" "-message" message "-title" title "-contentImage" icon "-group" pid)
    ((get-method notify-method :default) :default notification)))

(defmethod notify-method "Linux"
  [_ {:keys [message title icon] :as notification}]
  (if (program-exists? "notify-send")
    (sh "notify-send" title message "--icon" icon)
    ((get-method notify-method :default) :default notification)))

(defmethod notify-method :default
  [_ {:keys [message title]}]
  (printf "%s: %s" title message))

(defn ^{:boot/from :jeluard/boot-notify} notify!
  [s m]
  (notify-method (System/getProperty "os.name") (assoc m :message s)))
