.PHONY: run run-cli test package run-jar clean check-java check-clojure

run: check-java check-clojure package run-jar

run-cli: check-java check-clojure package
	@test -n "$(DIR)" || { echo "DIR is required. Example: make run-cli DIR=/path/to/folder"; exit 1; }
	java -jar target/x2s-sort.jar "$(DIR)" $(ARGS)

test:
	clojure -M:test

package:
	clojure -T:build uber

run-jar: package
	@if [ -n "$$DISPLAY" ] || [ -n "$$WAYLAND_DISPLAY" ]; then \
		java -jar target/x2s-sort.jar $(ARGS); \
	else \
		echo "GUI can't start: DISPLAY/WAYLAND_DISPLAY is not set."; \
		echo "Use CLI mode instead:"; \
		echo "  make run-cli DIR=/path/to/folder"; \
		exit 1; \
	fi

clean:
	rm -rf target

check-java:
	@command -v java >/dev/null 2>&1 || { echo "Java not found. Install Java (JRE/JDK) and retry."; exit 1; }

check-clojure:
	@command -v clojure >/dev/null 2>&1 || { echo "Clojure not found. Install Clojure CLI tools and retry."; exit 1; }
