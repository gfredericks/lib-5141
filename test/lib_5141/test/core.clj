(ns lib-5141.test.core
  (:import java.net.URL)
  (:require [fs.core :as fs]
            [ring.adapter.jetty :as jet]
            [clj-http core cookies [client :as client]])
  (:use lib-5141.core
        lib-5141.sessions
        clojure.test
        compojure.route
        compojure.core))

(defroutes test-server
  (GET "/foo" [] "hey folks")
  (GET "/bar" [] "you touched my bar")
  (POST "/upload" []
        "thanks for the file kid")
  (POST "/echo" {body :body}
        (format "The thing you sent me is %d bytes.\n"
                (-> body slurp count))))

(defn with-test-server*
  [func]
  (let [s (jet/run-jetty test-server {:port 35375, :join? false})]
    (try
      (Thread/sleep 2000)
      (func)
      (finally (.stop s)))))

(defmacro with-test-server [& body] `(with-test-server* (fn [] ~@body)))

(defmacro with-proxy-server
  [opts & body]
  `(let [stopper# (start-proxy-server "localhost" 35375 35376 ~opts)]
     (Thread/sleep 1000)
     (try ~@body (finally (stopper#)))))

(deftest identity-test
  (with-test-server
    (with-proxy-server {}
      (-> "http://localhost:35376/foo"
          URL.
          slurp
          (= "hey folks")
          (is)))))

(defn- post-file
  [f]
  (client/post "http://localhost:35376/upload"
               {:multipart [["title" "Foo"]
                            ["Content/type" "text/plain"]
                            ["file" f]]}))

(deftest little-file-test
  (with-test-server
    (with-proxy-server {}
      (fs/with-dir (fs/temp-dir)
        (let [f (fs/file "foo.txt")]
          (spit f "not much content")
          (-> (post-file f)
              :body
              (= "thanks for the file kid")
              (is)))))))

(defn- bigstring
  []
  (apply str (take (rand-int 1000000) (repeatedly #(rand-int 10)))))

(deftest big-file-test
  (with-test-server
    (with-proxy-server {}
      (fs/with-dir (fs/temp-dir)
        (let [f (fs/file "foo.txt")]
          (spit f (bigstring))
          (-> (post-file f)
              :body
              (= "thanks for the file kid")
              (is)))))))

(defroutes session-server
  (PUT "/:key/:value" [key value]
       (session-put! key value)
       "okay")
  (GET "/:key" [key]
       (pr-str (session-get key))))

;; A silly proxy that never forwards anything and just acts like the
;; above session-server
(def session-proxy
  {:request-fn (fn [req] [:respond (session-server req)])})

(deftest session-test
  (with-proxy-server session-proxy
    (binding [clj-http.core/*cookie-store* (clj-http.cookies/cookie-store)]
      ;; setting the key has an effect
      (is (= (:body (client/get "http://localhost:35376/farm"))
             "nil"))
      (is (= (:body (client/put "http://localhost:35376/farm/cows"))))
      (is (= (:body (client/get "http://localhost:35376/farm"))
             "\"cows\"")))
    ;; but not to clients without the cookie
    (is (= (:body (client/get "http://localhost:35376/farm"))
             "nil"))))