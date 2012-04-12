(ns lib-5141.util
  (:use [lamina.core :only [channel? lazy-channel-seq]]))


(defn read-channel-buffer
  "Returns a byte-array."
  [b]
  (let [my-bytes (byte-array (.capacity b))]
    (.getBytes b 0 my-bytes)
    my-bytes))

; REFACTOR: This is a temporary solution. Ideally this should
; be handled asynchronously, e.g. by using the aleph http client
; instead of clj-http
(defn slurp-channel
  "Reads the messages from the channel (presumably representing a
  streaming body) and returns a byte-array."
  [ch]
  {:pre [(channel? ch)]}
  (let [buffers (lazy-channel-seq ch),
        bss (map read-channel-buffer buffers),
        bs (vec (apply concat bss))
        ba (byte-array (count bs))]
    (doseq [i (range (count bs))]
      (aset-byte ba i (nth bs i)))
    ba))

(defn stacktrace
  [e]
  (with-out-str
    (.printStackTrace e (new java.io.PrintWriter *out*))))

