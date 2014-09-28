(ns storage.core
  (:require
    [clojure.core.async :as a :refer [put! <! chan go go-loop close!]]
    [clojure.java.io :as io]
    [clojure.pprint :refer [pprint]]
    [clojure.edn :as edn])
  (:import
    java.io.File))

;;-----------------------------------------------------------------------------

(defn- file->
  [fname]
  (io/file (.getAbsolutePath (io/as-file fname))))

(defn- init!
  [^File f]
  (when (and (.exists f) (.isDirectory f))
    (throw (ex-info "Storage location cannot be a directory." {:file f})))
  (when-not (.exists f)
      (.mkdirs (.getParentFile f))
      (spit f "{}")))

(defn- update!
  [store kws value]
  (swap! (:cache store) (fn [m]
                          (if (coll? kws)
                            (assoc-in m kws value)
                            (assoc m kws value)))))

(defn- write!
  [store]
  (spit (:file store) (with-out-str (pprint @(:cache store)))))

(defn- read!
  [store]
  (reset! (:cache store) (-> (slurp (:file store))
                             (edn/read-string))))

;; ought to have a channel for reading/writing
;;               a channel for logging a bad function application
;;               a function to use for the logging ch

(defn- store-loop!
  [store]
  (go-loop []
    (when-let [msg (<! (:ch store))]
      (try
        (case (:op msg)
          :init (read! store)
          :save (do (update! store (:kws msg) (:val msg))
                    (write! store)))
        (catch Throwable t
          (println "ERROR:" t)))
      (recur))))

;;-----------------------------------------------------------------------------

(defrecord Storage [ch file cache])

;;-----------------------------------------------------------------------------

(defn set-value!
  [store kws value]
  (put! (:ch store) {:op :save :kws kws :val value}))

(defn get-value
  ([store]
     @(:cache store))
  ([store kws]
     (if (coll? kws)
       (get-in @(:cache store) kws)
       (get @(:cache store) kws))))

(defn initialize!
  [store]
  (store-loop! store)
  (init! (:file store))
  (put! (:ch store) {:op :init}))

(defn mk-storage
  [filename]
  (let [storage-file (file-> filename)]
    (Storage. (chan 10) storage-file (atom {}))))

(comment
  ;; Interactive testing is the best.

  (def fname "test-store.clj")
  (.delete (io/as-file fname))
  (def config (mk-storage fname))

  (initialize! config)

  (set-value! config :foo {:bar {:baz 1}})

  (assert (= (get-value config)
             {:foo {:bar {:baz 1}}}))

  (assert (= (get-value config :foo)
             {:bar {:baz 1}}))

  (assert (= (get-value config [:foo :bar :baz])
             1))

  (set-value! config :ldap {:host "localhost"
                            :port 10389
                            :dn "uid=%s,ou=system"})

  (assert (= (get-value config [:ldap :port])
             10389))
  )
