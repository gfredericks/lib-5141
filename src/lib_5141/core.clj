(ns lib-5141.core
  (:import org.jboss.netty.buffer.ChannelBuffer
           java.io.ByteArrayInputStream
           java.io.SequenceInputStream)
  (:use lamina.core aleph.http))

(defn fix-body
  "If body is a string, convert it to a channel like aleph likes."
  [body]
  (if (string? body)
    (let [c (channel)]
      (enqueue c body)
      (close c)
      c)
    body))

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
      (update-in [:body] fix-body)
      http-request))

(defn async-handler
  [forward-host
   forward-port
   {:keys [request-fn response-fn async-request-fn]
    :or {response-fn (fn [a b] a)}}]
  {:pre [(not (and request-fn async-request-fn))]}
  (fn [ch request]
    (let [request (into {} request)
          request-fn (if (or request-fn async-request-fn)
                       request-fn
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
        (catch Throwable t (return-error t))))))

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
  ([forward-host forward-port listen-port opts]
     (start-http-server
      (async-handler forward-host forward-port opts)
      {:port listen-port})))

(defn- seq->enumeration
  [coll]
  (let [state (ref coll)]
    (reify java.util.Enumeration
      (hasMoreElements [this] (not (empty? @state)))
      (nextElement [this]
        (dosync
         (let [x (first @state)]
           (alter state rest)
           x))))))

(defn- byte-arrays->input-stream
  [byte-arrays]
  (->> byte-arrays
       (map #(ByteArrayInputStream. %))
       seq->enumeration
       SequenceInputStream.))

(defn- aleph-body->input-stream
  [body]
  (cond (instance? ChannelBuffer body)
        (-> body .array ByteArrayInputStream.)

        (channel? body)
        ;; wrap the buffer-channel in an input stream
        (->> body
             lazy-channel-seq
             (map (memfn array))
             byte-arrays->input-stream)

        (instance? java.io.InputStream body)
        body

        :else (throw (Exception. (str "Unknown body type: " (type body))))))

(defn ring-handler
  "Returns a synchronous ring handler, defeating the entire purpose of
  using aleph. opts are same as start-proxy-server"
  ([forward-host forward-port] (ring-handler forward-host forward-port {}))
  ([forward-host forward-port opts]
     (let [h (async-handler forward-host forward-port opts)]
       (fn [req]
         (let [c (channel)]
           (h c req)
           (try (-> c read-channel deref (update-in [:body] aleph-body->input-stream))
                (finally (close c))))))))