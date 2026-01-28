#!/bin/sh
mvn -q package
java -jar target/*.jar
