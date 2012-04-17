(ns lib-5141.core
  (:import org.jboss.netty.buffer.ChannelBuffer)
  (:use lamina.core aleph.http))

(defn start-proxy-server
  "request-fn will be called with the request and should return
  either of:
    [:forward new-req]
    [:respond response]
  when the :forward route is taken the response-fn will be called with
  two arguments -- the response from the remote server and the original
  request returned from the request-fn (this is so you can share information
  from the request-fn to the response-fn)."
  ([a b c] (start-proxy-server a b c {}))
  ([forward-host
    forward-port
    listen-port
    {:keys [request-fn response-fn] :or {request-fn (fn [a] [:forward a])
                                         response-fn (fn [a b] a)}}]
     (let [forward-request
           ;; returns an aleph channel
           (fn [req]
             (-> req
                 ;; not sure all these are necessary for aleph
                 ;; TODO: probably wise to change the "host" header also
                 (assoc :server-port forward-port
                        :server-name forward-host
                        :follow-redirects false
                        :method (:request-method req)
                        :scheme (-> req :scheme name))
                 (http-request)))]
       (start-http-server
        (fn [ch request]
          (let [request (into {} request)
                request (request-fn request)
                return-response (partial enqueue-and-close ch)
                return-error (fn [e]
                               (prn "RETURNING ERROR RESPONSE" (str e))
                               (.printStackTrace e)
                               (return-response {:status 500 :body (str e)}))]
            (try
              (let [[action thing] request]
                (cond (= :forward action)
                      (let [response-channel (forward-request thing)]
                        (on-success response-channel
                                    (fn [resp]
                                      (-> resp
                                          (response-fn thing)
                                          return-response
                                          (try (catch Throwable t (return-error t)))))))
                      (= :respond action) (enqueue ch thing)))
              (catch Throwable t (return-error t)))))
        {:port listen-port}))))