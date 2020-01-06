#!/bin/sh

# stop the script on first error
set -e -x


if [ $# -ne 2 ]; then
	echo "Usage:"
	echo ""
	echo "$0 <release-version> <next-dev-version>"
	echo ""
	echo "- release-version - The version which should be tagged (e.g. '1.0')"
	echo "- next-dev-version - Next version which will be used for further development (e.g. '1.1-SNAPSHOT')"
	echo ""
	exit
fi

function divider () {
	echo ""
	echo "==> $1"
	echo ""
}

# Actualize local develop & master branches

divider "Create release branch"
git branch release/$1 develop

divider "Maven release version $1"
git checkout release/$1
mvn versions:set -DnewVersion="$1" -DgenerateBackupPoms=false
git add "*pom.xml"
git commit -m "release/$1 Maven project version set to $1"

divider "Finish the release branch"
git checkout master
git merge -m "Merge branch 'release/$1' into master" --no-ff release/$1
git branch -d release/$1

divider "Build new production artifacts"
mvn clean deploy -P release

divider "Tag the new relase version"
git tag -m "Version $1" -a v$1 

divider "Maven next development version $2"
git checkout develop
git merge -m "Merge branch 'master' with version '$1' into develop" --no-ff master
mvn versions:set -DnewVersion="$2" -DgenerateBackupPoms=false
git add "*pom.xml"
git commit -m "release/$1 Development continues on version $2"

divider "Push the changes to the 'origin' repo"
git push --tags origin master develop
