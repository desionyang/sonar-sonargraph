rem -Denforcer.skip=true 

mvn -e -fn clean package com.hello2morrow.sonargraph:maven-sonargraph-plugin:7.2.3:architect-report -Dsonargraph.file=./sonar-sonargraph-plugin.sonargraph -Dsonargraph.prepareForSonar=true sonar:sonar -Dsonar.exclusions=**/generated-sources/**