(ns lib-5141.core
  (:use lamina.core aleph.http)
  (:use lib-5141.util)
  #_(:use [clojure.core.match :only [match]])
  (:require [clj-http
             [client :as client]
             [core :as http-core]]))


(def #^:private request
  (-> http-core/request
      client/wrap-query-params
      client/wrap-user-info
      client/wrap-url
      client/wrap-redirects
      client/wrap-decompression
      client/wrap-input-coercion
      client/wrap-output-coercion
      client/wrap-exceptions
      client/wrap-basic-auth
      client/wrap-accept
      client/wrap-accept-encoding
      client/wrap-content-type
      client/wrap-form-params
      client/wrap-method))

;; The API here doesn't seem obvious...we want to support at
;; least 2 use cases, and the user should be able to choose
;; them after seeing the request:
;;   - Modifying request and response
;;   - short-circuiting and responding immediately
;; also we probably want them to be able to determine the
;; response editing before
;;
;; Is this really all we need here? Probably will want to take
;; care of the body-is-a-channel thing as well.
;; Also we could take options to implement the other behavior
;; we might want...
;;
;; Weakness in this API: it is synchronous? maybe that's just an
;; assumption we're making...I just realized that the
;; returning-a-response-func thing could be simplified by taking a
;; response func in the top level function here and then just passing
;; the original modified request in to it as well, so that the
;; request-fn could communicate with the response-fn. Dunno maybe?
(defn start-proxy-server
  "request-fn will be called with the request and should return
  either of:
    [:forward new-req]
    [:respond response]
  when the :forward route is taken the response-fn will be called with
  two arguments -- the response from the remote server and the original
  request returned from the request-fn (this is so you can share information
  from the request-fn to the response-fn)."
  [forward-host
   forward-port
   listen-port
   request-fn
   response-fn]
  (let [forward-request
        (fn [req]
          (-> req
              (assoc :server-port forward-port
                     :server-name forward-host
                     :throw-exceptions false
                     :follow-redirects false
                     :method (:request-method req))
              (request)
              (->> (into {}))
              ((fn [resp]
                 (print "got response back from server")
                 resp))))
        response-fn (or response-fn (fn [a b] a))]
    (start-http-server
     (fn [ch request]
       (prn "GETTING REQUEST FOR" (:uri request) (:request-method request))
       (let [request (into {} request)
             request (request-fn request)]
         (enqueue ch
                  (try
                    (let [[action thing] request]
                      (cond (= :forward action) (-> thing forward-request (response-fn thing))
                            (= :respond action) thing))
                    ;; I __think__ this wasn't working...?
                    #_(match request
                           [:forward new-req] 
                           (let [response (response-fn (forward-request new-req) new-req)]
                             (println "INDIRECT RESPONSE")
                             (prn response)
                             response)
                           [:respond response]
                           (do (println "DIRECT RESPONSE")
                               (prn response)
                               response))
                    (catch Throwable t
                      (prn "RETURNING ERROR RESPONSE")
                      (.printStackTrace t)
                      {:status 500 :body (str t)}))))
       (close ch))
     {:port listen-port})))