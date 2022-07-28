LATEST_TAG?=`git tag|sort -t. -k 1,1n -k 2,2n -k 3,3n -k 4,4n | tail -1`

help:
	cat Makefile.txt

clean:
	rm -rf build
	./gradlew clean && cd examples && ./gradlew clean

.PHONY: build
build:
	./gradlew build

build-fast:
	./gradlew build -Pcheck=false

.PHONY: examples
examples:
	cd examples && ./gradlew clean build

release:
	./gradlew release

publish-snapshot:
	./gradlew build publishMavenJavaPublicationToMavenLocal publishMavenJavaPublicationToMavenRepository

publish:
	git checkout tags/${LATEST_TAG}
	./gradlew build publishMavenJavaPublicationToMavenLocal publishMavenJavaPublicationToMavenRepository
	git checkout main

publish-plugins-snapshot:
	./gradlew publishPlugins

publish-plugins:
	git checkout tags/${LATEST_TAG}
	./gradlew publishPlugins
	git checkout main

.PHONY: doc
doc: build
	rm -rf doc/javadoc/
	cp -r build/docs/javadoc doc/
