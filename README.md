# Overview #

https://docs.xebialabs.com/xl-release/how-to/implement-a-custom-failure-handler.html

# CI status #

[![Build Status][xlr-on-failure-handler-plugin-travis-image] ][xlr-on-failure-handler-plugin-travis-url]
[![Codacy][xlr-on-failure-handler-plugin-codacy-image] ][xlr-on-failure-handler-plugin-codacy-url]
[![Code Climate][xlr-on-failure-handler-plugin-code-climate-image] ][xlr-on-failure-handler-plugin-code-climate-url]
[![License: MIT][xlr-on-failure-handler-plugin-license-image] ][xlr-on-failure-handler-plugin-license-url]
[![Github All Releases][xlr-on-failure-handler-plugin-downloads-image] ]()


[xlr-on-failure-handler-plugin-travis-image]: https://travis-ci.org/xebialabs-community/xlr-on-failure-handler-plugin.svg?branch=master
[xlr-on-failure-handler-plugin-travis-url]: https://travis-ci.org/xebialabs-community/xlr-on-failure-handler-plugin
[xlr-on-failure-handler-plugin-codacy-image]: https://api.codacy.com/project/badge/Grade/57314806d4eb4f6a855707edc6c8ef75
[xlr-on-failure-handler-plugin-codacy-url]: https://www.codacy.com/app/joris-dewinne/xlr-on-failure-handler-plugin
[xlr-on-failure-handler-plugin-code-climate-image]: https://codeclimate.com/github/xebialabs-community/xlr-on-failure-handler-plugin/badges/gpa.svg
[xlr-on-failure-handler-plugin-code-climate-url]: https://codeclimate.com/github/xebialabs-community/xlr-on-failure-handler-plugin
[xlr-on-failure-handler-plugin-license-image]: https://img.shields.io/badge/License-MIT-yellow.svg
[xlr-on-failure-handler-plugin-license-url]: https://opensource.org/licenses/MIT
[xlr-on-failure-handler-plugin-downloads-image]: https://img.shields.io/github/downloads/xebialabs-community/xlr-on-failure-handler-plugin/total.svg

## Installation ##

Place the latest released version under the `plugins` dir.

## Customizations ##

The following additional changes have been made compared to the one described in the [documentation](https://docs.xebialabs.com/xl-release/how-to/implement-a-custom-failure-handler.html):
+ Also checking if task is part of an `xlrelease.SequentialGroup`
+ On failure adds a variable with name `releaseFailedTaskTitle` that will contain the title of the failed task.
+ On failure adds a variable with name `releaseFailedTaskLink` that will contain a direct link to the failed task.
