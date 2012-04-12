(ns lib-5141.core
  (:use lamina.core aleph.http)
  (:use lib-5141.util)
  #_(:use [clojure.core.match :only [match]]))


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
        ;; returns an aleph channel
        (fn [req]
          (-> req
              (assoc :server-port forward-port
                     :server-name forward-host
                     :throw-exceptions false
                     :follow-redirects false
                     :method (:request-method req))
              (http-request)))
        response-fn (or response-fn (fn [a b] a))]
    (start-http-server
     (fn [ch request]
       (let [request (into {} request)
             request (request-fn request)
             return-response (partial enqueue ch)
             return-error (fn [e]
                            (prn "RETURNING ERROR RESPONSE")
                            (.printStackTrace e)
                            (return-response {:status 500 :body (str e)}))]
         (try
           (let [[action thing] request]
             (cond (= :forward action)
                   (let [response-channel (forward-request thing)]
                     (on-success response-channel
                                 (fn [resp]
                                   (-> resp
                                       response-fn
                                       return-response
                                       (try (catch Throwable t (return-error t)))))))
                   (= :respond action) (enqueue ch thing)))
           ;; I __think__ this wasn't working...?
           #_(match request
                    [:forward new-req]
                    (let [response (response-fn (forward-request new-req) new-req)]
                      response)
                    [:respond response]
                    response)
           (catch Throwable t (return-error t))))
       (close ch))
     {:port listen-port})))