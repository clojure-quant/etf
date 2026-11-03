(ns etf.http
  "Minimal HTTP client built on java.net.http, so no extra HTTP dependency is needed."
  (:import [java.net URI]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest HttpResponse$BodyHandlers]
           [java.time Duration]))

(def ^:private default-headers
  {"User-Agent" "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
   "Accept-Language" "en-US,en;q=0.9"})

(defonce ^:private client
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 30))
      (.followRedirects HttpClient$Redirect/NORMAL)
      (.build)))

(defn get-string
  "GET `url` and return the response body as a string.
  Optional `headers` are merged over the default browser-like headers."
  ([url] (get-string url nil))
  ([url headers]
   (let [builder (-> (HttpRequest/newBuilder (URI/create url))
                     (.timeout (Duration/ofSeconds 120))
                     (.GET))]
     (doseq [[k v] (merge default-headers headers)]
       (.header builder (name k) (str v)))
     (-> client
         (.send (.build builder) (HttpResponse$BodyHandlers/ofString))
         (.body)))))