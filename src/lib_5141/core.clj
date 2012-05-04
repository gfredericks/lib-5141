(ns lib-5141.core
  (:use lamina.core aleph.http))

(defn- forward-request
  [forward-host forward-port req]
  (-> req
      ;; not sure all these are necessary for aleph
      ;; TODO: probably wise to change the "host" header also
      (assoc :server-port forward-port
             :server-name forward-host
             :follow-redirects false
             :method (:request-method req)
             :scheme (-> req :scheme name))
      (update-in [:headers] dissoc "host")
      (http-request)))

(defn start-proxy-server
  "Three options are available:

  :request-fn
    if given, will be called with the request and should return
    either of:
      [:forward new-req]
      [:respond response]

  :response-fn
    if given, any response from a forwarded request will be passed
    through this function, along with the (maybe modified) request
    that was forwarded (so it must be a 2-arity function). The return
    value of the function will be the actual response.

  :async-request-fn
    if given, will be called with three args -- the request, a forwarder
    function, and a responder function. Exactly one of the two functions
    should be called at some point (only once) with the request.

  It is an error to supply both a request-fn and an async-request-fn"
  ([a b c] (start-proxy-server a b c {}))
  ([forward-host
    forward-port
    listen-port
    {:keys [request-fn response-fn async-request-fn]
     :or {#_request-fn #_(fn [a] [:forward a])
          response-fn (fn [a b] a)}}]
     (assert (not (and request-fn async-request-fn)))
     (start-http-server
      (fn [ch request]
        (let [request (into {} request)
              request-fn (when-not (or request-fn async-request-fn)
                           (fn [a] [:forward a]))
              return-response (partial enqueue-and-close ch)
              return-error (fn [e]
                             (prn "RETURNING ERROR RESPONSE" (str e))
                             (.printStackTrace e)
                             (return-response {:status 500 :body (str e)}))
              forward (fn [req]
                        (let [response-channel (forward-request forward-host forward-port req)]
                          (on-success response-channel
                                      (fn [resp]
                                        (-> resp
                                            (response-fn req)
                                            return-response
                                            (try (catch Throwable t (return-error t))))))))]
          (try
            (if request-fn
              (let [[action thing] (request-fn request)]
                (cond (= :forward action) (forward thing)
                      (= :respond action) (return-response thing)
                      :else (throw (Exception. "Bad return value from request-fn."))))
              (let [called (atom 0)
                    limit-calls (fn [func]
                                  (fn [arg]
                                    (if (> (swap! called inc) 1)
                                      (throw (Exception. "Called forward/reply functions more than once!"))
                                      (func arg))))]
                (async-request-fn
                 request
                 (limit-calls forward)
                 (limit-calls return-response))))
            (catch Throwable t (return-error t)))))
      {:port listen-port})))