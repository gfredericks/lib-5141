(ns lib-5141.test.core
  (:import java.net.URL)
  (:require [fs.core :as fs]
            [ring.adapter.jetty :as jet]
            [clj-http.client :as client]
            [clojure.string :as s])
  (:use lib-5141.core
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
  ([func] (with-test-server* test-server func))
  ([server func]
     (let [s (jet/run-jetty server {:port 35375, :join? false})]
       (try
         (Thread/sleep 2000)
         (func)
         (finally (.stop s))))))

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

(def inspector-server
  (fn [req]
    (let [req (assoc req :body (-> req :body slurp))]
      {:status 200
       :body (pr-str req)})))

(deftest host-header-test
  (with-test-server* inspector-server
    (fn []
      (with-proxy-server {}
        (let [hosts (-> "http://localhost:35376/foo/bar"
                        URL.
                        slurp
                        read-string
                        :headers
                        (get "host")
                        (s/split #","))]
          (is (= 1 (count hosts))))))))

(deftest async-test
  (let [reqs (atom [])
        test-server (fn [{:keys [request-method uri]}]
                      (swap! reqs conj [request-method uri])
                      {:status 200 :body "ok"})
        forwarder-atom (atom nil)
        proxy-req (fn [req forwarder replier]
                    (reset! forwarder-atom [req forwarder]))]
    (with-test-server* test-server
      (fn []
        (with-proxy-server {:async-request-fn proxy-req}
          (future (-> "http://localhost:35376/foo/bar"
                      URL.
                      slurp))
          (is (empty? @reqs))
          (Thread/sleep 50)
          (is (empty? @reqs))
          (let [[req forwarder] @forwarder-atom]
            (forwarder req))
          (Thread/sleep 50)
          (is (= [[:get "/foo/bar"]] @reqs)))))))