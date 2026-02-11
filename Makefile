.PHONY: run test package run-jar clean check-java check-clojure

run: check-java check-clojure package run-jar

test:
	clojure -M:test

package:
	clojure -T:build uber

run-jar: package
	java -jar target/x2s-sort.jar

clean:
	rm -rf target

check-java:
	@command -v java >/dev/null 2>&1 || { echo "Java not found. Install Java (JRE/JDK) and retry."; exit 1; }

check-clojure:
	@command -v clojure >/dev/null 2>&1 || { echo "Clojure not found. Install Clojure CLI tools and retry."; exit 1; }
