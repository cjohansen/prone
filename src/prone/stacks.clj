(ns prone.stacks
  "Extract data from stack traces and represent them with plain maps"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- find-loaded-from [url]
  (when-let [path (and (io/resource url)
                       (.getPath (io/resource url)))]
    (or (second (re-find #"/([^/]+)\.jdk/" path))
        (second (re-find #"/([^/]+)\.jar!" path)))))

(defn- file-name-to-namespace [s]
  (-> s
      (str/replace "_" "-")
      (str/replace "/" ".")
      (str/replace #"\.cljc?" "")))

(defn cljc? [frame]
  (re-find #"\.cljc$" (.getFileName frame)))

(defn- normalize-frame-clj [frame]
  (let [fn-name (-> frame
                    .getClassName
                    (str/replace "_" "-")
                    (str/replace #"^.*\$" ""))
        class-path-url (-> frame
                           .getClassName
                           (str/replace "." "/")
                           (str/replace #"\$.*" (if (cljc? frame)
                                                  ".cljc"
                                                  ".clj")))]
    {:class-path-url class-path-url
     :loaded-from (find-loaded-from class-path-url)
     :method-name (if (re-find #"^fn--\d+$" fn-name) "[fn]" fn-name)
     :package (-> frame
                  .getClassName
                  (str/replace "_" "-")
                  (str/replace #"\$.*" ""))
     :lang :clj}))

(defn- normalize-frame-java [frame]
  (let [class-path-url-stub (-> frame
                                .getClassName
                                (str/replace "." "/"))]
    {:class-path-url (-> class-path-url-stub
                         (str/replace #"\$[^/]+$" "") ;; remove inner classes
                         (str ".java"))
     :loaded-from (find-loaded-from (str class-path-url-stub ".class"))
     :method-name (.getMethodName frame)
     :class-name (-> frame
                     .getClassName
                     (str/split #"\.")
                     last)
     :package (-> frame
                  .getClassName
                  (str/split #"\.")
                  butlast
                  (->> (str/join ".")))
     :lang :java}))

(defn normalize-frame [frame]
  (if (.getFileName frame)
    (merge {:file-name (.getFileName frame)
            :line-number (.getLineNumber frame)}
           (if (re-find #"\.cljc?$" (.getFileName frame))
             (normalize-frame-clj frame)
             (normalize-frame-java frame)))
    {:method-name (.getMethodName frame)
     :class-name (-> frame
                     .getClassName
                     (str/split #"\.")
                     last)
     :package (-> frame
                  .getClassName
                  (str/split #"\.")
                  butlast
                  (->> (str/join ".")))}))

(defn- add-data [normalized exception]
  (if-let [data (and (instance? clojure.lang.ExceptionInfo exception)
                     (.data exception))]
    (assoc normalized :data data)
    normalized))

(declare normalize-exception)

(defn- add-cause [normalized exception]
  (if-let [cause (.getCause exception)]
    (assoc normalized :caused-by (normalize-exception cause))
    normalized))

(defn- add-frame-from-message [ex]
  (if-let [data (and (:message ex)
                     (re-find #"\(([^(]+.cljc?):(\d+):(\d+)\)" (:message ex)))]
    (let [[_ path line column] data]
      (if (io/resource path)
        (update-in ex [:frames]
                   #(conj % {:lang :clj
                             :package (file-name-to-namespace path)
                             :method-name nil
                             :loaded-from nil
                             :class-path-url path
                             :file-name (re-find #"[^/]+.cljc?" path)
                             :line-number (Integer. line)
                             :column (Integer. column)}))
        ex))
    ex))

(defn normalize-exception
  "Convert an exception object to a map. Unify the stack trace elements, so that
  frames from Java sources and frames from Clojure sources are represented with
  the same data structure."
  [exception]
  (let [type (.getName (type exception))]
    (-> {:message (.getMessage exception)
         :type type
         :class-name (last (str/split type #"\."))
         :frames (->> exception .getStackTrace (map normalize-frame))}
        (add-data exception)
        (add-cause exception)
        (add-frame-from-message))))
