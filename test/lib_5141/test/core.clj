(ns lib-5141.test.core
  (:require [clojure.java.io :as io]
            [fs.core :as fs]
            [compojure.route :as route]
            [compojure.core :as ccore]
            [ring.adapter.jetty :as jet])
  (:use [lib-5141.core])
  (:use [clojure.test]))

(defn- write-big-file
  "Spits a megabyte of random data into a file."
  [filename]
  (with-open [writer (-> filename io/as-file io/output-stream (io/make-writer {}))]
    (doseq [_ (range 1000000)]
      (.write writer (rand-int 128)))))

(defroutes test-server
  (GET "/foo" [] "hey folks")
  (GET "/bar" [] "you touched my bar"))

(defn with-test-server*
  [func]
  (let [s (jet/run-jetty test-server {:port 35375, :join? false})]
    (try
      (func)
      (finally (.stop s)))))

(defmacro with-test-server [& body] `(with-test-server* (fn [] ~@body)))

(def first-arg-identity (fn [a & bs] a))

(deftest identity-test
  (with-test-server
    (let [stopper (start-proxy-server "localhost" 35375 35376
                                     identity
                                     first-arg-identity)]
      (try
        (-> "http://localhost:35376/foo"
            URL.
            slurp
            (= "hey folks")
            (is))
        (finally (stopper))))))