(ns lib-5141.test.core
  (:import java.net.URL)
  (:require [clojure.java.io :as io]
            [fs.core :as fs]
            #_[compojure.core :as ccore]
            [ring.adapter.jetty :as jet]
            [clj-http.client :as client])
  (:use [lib-5141.core]
        [clojure.test]
        compojure.route
        compojure.core))

(defn- write-big-file
  "Spits a megabyte of random data into a file."
  [filename]
  (with-open [writer (-> filename io/as-file io/output-stream (io/make-writer {}))]
    (doseq [_ (range 1000000)]
      (.write writer (rand-int 128)))))

(defroutes test-server
  (GET "/foo" [] "hey folks")
  (GET "/bar" [] "you touched my bar")
  (POST "/upload" [] "thanks for the file kid"))

(defn with-test-server*
  [func]
  (let [s (jet/run-jetty test-server {:port 35375, :join? false})]
    (try
      (func)
      (finally (.stop s)))))

(defmacro with-test-server [& body] `(with-test-server* (fn [] ~@body)))

(defn forward-identity [a] [:forward a])
(defn first-arg-identity [a & bs] a)

(defmacro with-proxy-server
  [req-fn resp-fn & body]
  `(let [stopper# (start-proxy-server "localhost" 35375 35376 ~req-fn ~resp-fn)]
     (try ~@body (finally (stopper#)))))

(deftest identity-test
  (with-test-server*
    (with-proxy-server forward-identity first-arg-identity
      (-> "http://localhost:35376/foo"
          URL.
          slurp
          (= "hey folks")
          (is)))))

(deftest little-file-test
  (with-test-server*
    (with-proxy-server forward-identity first-arg-identity
      (fs/with-dir (fs/temp-dir)
        (spit (fs/file "foo.txt") "not much content")
        (-> "http://localhost:35376/upload"
            (client/post
             {:multipart [["title" "Foo"]
                          ["Content/type" "text/plain"]
                          ["file" (fs/file "foo.txt")]]})
            :body
            (= "thanks for the file kid")
            (is))))))