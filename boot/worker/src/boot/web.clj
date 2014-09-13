(ns boot.web
  (:require
   [clojure.java.io :as io]
   [boot.xml        :as xml]
   [boot.pod        :as pod]
   [boot.util       :as util]))

(xml/decelems
  description display-name param-name param-value servlet-class servlet-name
  url-pattern web-app servlet init-param servlet-mapping)

(xml/defelem init-params [[& kvs]]
  (for [[k v] kvs :when v]
    (init-param (param-name (name k)) (param-value (str v)))))

(defn web-xml [name desc serve create destroy]
  (web-app
    :xmlns              "http://java.sun.com/xml/ns/javaee"
    :xmlns:xsi          "http://www.w3.org/2001/XMLSchema-instance"
    :xsi:schemaLocation "http://java.sun.com/xml/ns/javaee http://java.sun.com/xml/ns/javaee/web-app_3_0.xsd"
    :version            "3.0"
    :metadata-complete  "true"
    (display-name name)
    (description desc)
    (servlet
      (servlet-name name)
      (servlet-class "tailrecursion.ClojureAdapterServlet")
      (init-params :create create :serve serve :destroy destroy))
    (servlet-mapping (servlet-name name) (url-pattern "/*"))))

(defn spit-web! [tgt serve create destroy]
  (util/info "Creating web.xml...")
  (let [web-inf (io/file tgt "WEB-INF")]
    (spit
      (doto (io/file web-inf "web.xml") io/make-parents)
      (pr-str (web-xml "boot-webapp" "boot-webapp" serve create destroy)))
    (pod/copy-resource
      "tailrecursion/ClojureAdapterServlet.class"
      (io/file web-inf "classes" "tailrecursion" "ClojureAdapterServlet.class"))))
