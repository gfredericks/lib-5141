# lib-5141

lib-5141 is a small Clojure library for setting up basic http proxies and modifying
their behavior. It uses aleph for most of the details, and so shouldn't take up
more resources than necessary.

When creating a proxy you can give it a request function, which will be called with
incoming requests and can decide to forward the request to the server (with or without
modifications), or short-circuit and return a response directly. You can also give
a response function which can transform responses on their way back.

## Usage

See the source code for documentation.

## License

Copyright (C) 2012 Gary Fredericks

Distributed under the Eclipse Public License, the same as Clojure.
