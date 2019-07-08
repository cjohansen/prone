(ns prone.prep-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [datomic.api :as d]
            [prone.prep :refer [prep-error-page prep-debug-page]])
  (:import [java.io ByteArrayInputStream]))

(defn prep-frames [frames & [application-name]]
  (-> (prep-error-page {:frames frames} {} {} application-name)
      :error :frames))

(deftest source-for-frames
  (is (re-find #"prone.prep-test"
               (:code (:source (first (prep-frames [{:class-path-url "prone/prep_test.clj"}]))))))
  (is (= "(unknown source file)"
         (:failure (:source (first (prep-frames [{}]))))))
  (is (= "(could not locate source file on class path)"
         (:failure (:source (first (prep-frames [{:class-path-url "plone/plep_test.clj"}])))))))

(deftest id-for-frames
  (is (= [0 1]
         (map :id (prep-frames [{:class-path-url "prone/prep_test.clj"}
                                {:class-path-url "prone/prep_test.clj"}])))))

(deftest application-frames
  (is (= ["a"] (->> (prep-frames [{:name "a" :package "prone.prep-test"}
                                  {:name "b" :package "plone.plep-test"}]
                                 ["prone"])
                    (filter :application?)
                    (map :name)))))

(deftest frame-selection
  (is (= :application (:src-loc-selection (prep-error-page {:frames []} {} {} "")))))

(defrecord DefLeppard [num-hands])

(def conn (do (d/create-database "datomic:mem://test-db")
              (d/connect "datomic:mem://test-db")))

(deftest no-unreadable-forms
  (is (= {:name "John Doe"
          :age 37
          :url {:prone.prep/value "http://example.com"
                :prone.prep/original-type "java.net.URL"}
          :body {:prone.prep/value "Hello"
                 :prone.prep/original-type "java.io.ByteArrayInputStream"}
          :closed-stream {:prone.prep/value nil
                          :prone.prep/original-type "java.io.BufferedInputStream"}
          :lazy '(2 3 4)
          :record {:prone.prep/value {:num-hands 1}
                   :prone.prep/original-type "prone.prep_test.DefLeppard"}
          :datomic {:conn {:prone.prep/original-type "datomic.peer.LocalConnection",
                           :prone.prep/value ""}
                    :db {:prone.prep/original-type "datomic.db.Db",
                         :prone.prep/value ""}
                    :entity {:prone.prep/original-type "datomic.query.EntityMap",
                             :prone.prep/value "#:db{:id 1}"}}}
         (-> (prep-error-page {} {} {:session {:name "John Doe"
                                               :age 37
                                               :url (java.net.URL. "http://example.com")
                                               :body (ByteArrayInputStream. (.getBytes "Hello"))
                                               :closed-stream (doto (io/input-stream "http://google.com") .close)
                                               :lazy (map inc [1 2 3])
                                               :record (DefLeppard. 1)
                                               :datomic (let [db (d/db conn)]
                                                          {:conn conn :db db :entity (d/entity db 1)})}}
                              "")
             :browsables first :data :session))))

(deftest avoid-really-long-strings
  (is (= {:content {:prone.prep/value "ssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss..."
                    :prone.prep/original-type "String with 20000 chars"}}
         (-> (prep-error-page {} {} {:content (str/join (repeat 20000 "s"))} "")
             :browsables first :data))))

(deftype SillyPrintString [i])

(defmethod clojure.core/print-method SillyPrintString [x writer]
  (.write writer "#my/silly-print-string ")
  (.write writer (str (mod (.i x) 10))))

(deftest properly-handle-sets
  (is (= {:content [{:prone.prep/original-type "prone.prep_test.SillyPrintString"
                     :prone.prep/value "#my/silly-print-string 7"}
                    :prone.prep/set?]}
         (-> (prep-error-page {} {} {:content #{(SillyPrintString. 7)}} "")
             :browsables first :data)))

  (testing "two items in a set that look the same after pr-str, are still both kept"
    (is (= {:content [{:prone.prep/original-type "prone.prep_test.SillyPrintString"
                       :prone.prep/value "#my/silly-print-string 7"}
                      {:prone.prep/original-type "prone.prep_test.SillyPrintString"
                       :prone.prep/value "#my/silly-print-string 7"}
                      :prone.prep/set?]}
           (-> (prep-error-page {} {} {:content #{(SillyPrintString. 7)
                                                  (SillyPrintString. 17)}} "")
               :browsables first :data)))))

(defn prep-debug [debug]
  (prep-debug-page debug {}))

(deftest prep-debug-auxilliary-info
  (let [class-path-url "prone/debug_test.clj"]

    (is (= :clj (:lang (first (:debug-data (prep-debug [{}]))))))

    (is (= "test/prone/debug_test.clj"
           (:file-name (first (:debug-data (prep-debug [{:class-path-url class-path-url}]))))))

    (is (= "prone/debug_test.clj"
           (:class-path-url (first (:debug-data (prep-debug [{:class-path-url class-path-url}]))))))

    (is (= "prone.debug-test"
           (:package (first (:debug-data (prep-debug [{:class-path-url class-path-url}]))))))

    (let [source (:source (first (:debug-data (prep-debug [{:class-path-url class-path-url}]))))]
      (is (re-find #"^\(ns prone\.debug-test" (:code source)))
      (is (= 0 (:offset source))))))
