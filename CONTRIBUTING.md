# Contributing to Zeebe

* [Build Zeebe from source](#build-zeebe-from-source)
* [Report issues or contact developers](#report-issues-or-contact-developers)
* [GitHub Issue Guidelines](#github-issue-guidelines)
* [Create a Pull Request](#create-a-pull-request)
* [Commit Message Guidelines](#commit-message-guidelines)
* [Contributor License Agreement](#contributor-license-agreement)
* [Licenses](#licenses)
* [Code of Conduct](#code-of-conduct)

## Build Zeebe from source

Zeebe is a multi-module maven project. To build all components,
run the command: `mvn clean install -DskipTests` in the root folder.

> NOTE: The Java and the Go modules are built and tested with Docker 20.10.5 [with IPv6 support](https://docs.docker.com/config/daemon/ipv6/).

The resulting Zeebe distribution can be found in the folder `dist/target`, i.e.
```
dist/target/zeebe-distribution-X.Y.Z-SNAPSHOT.tar.gz
dist/target/zeebe-distribution-X.Y.Z-SNAPSHOT.zip
```

This is a small overview of the contents of the different modules:
- `util` contains custom implementations of building blocks like an actor scheduler, buffer allocations, metrics. Its parts are used in most of the other modules
- `protocol` contains the SBE definition of the main message protocol
- `bpmn-model` is a Java API for BPMN process definitions used for parsing etc.
- `msgpack-*` is custom msgpack implementation with extensions to evaluate json-path expressions on msgpack objects
- `dispatcher` is a custom implementation of message passing between threads
- `service-container` is a custom implementation to manage dependencies between different services
- `logstreams` is an implementation of a append only log backed by the filesystem
- `transport` is our abstraction over network transports
- `gateway` is the implementation of the gRPC gateway, using our SBE-based protocol to communicate with brokers
- `gateway-protocol` is the gRPC definitions for the Zeebe client-to-gateway protocol
- `zb-db` is our RocksDB wrapper for state management
- `engine`  is the implementation of the event stream processor
- `broker` contains the Zeebe broker which is the server side of Zeebe
- `client-java` contains the Java Zeebe client

## Report issues or contact developers

Zeebe uses GitHub issues to organize the development process. If you want to
report a bug or request a new feature feel free to open a new issue on
[GitHub][issues].

If you are reporting a bug, please help to speed up problem diagnosis by
providing as much information as possible. Ideally, that would include a small
[sample project][sample] that reproduces the problem.

If you have a general usage question please ask on the [forum][] or [slack][] channel.

## GitHub Issue Guidelines

Every issue should have a meaningful name and a description which either
describes:
- a new feature with details about the use case the feature would solve or
  improve
- a problem, how we can reproduce it and what would be the expected behavior
- a change and the intention how this would improve the system

## Create a Pull Request

Zeebe follows a
[gitflow](https://nvie.com/posts/a-successful-git-branching-model/) workflow.
The `develop` branch contains the current in-development state of the project. The `master` branch contains the latest stable release.

To work on an issue, follow the following steps:

1. Check that a [GitHub issue][issues] exists for the task you want to work on.
   If one does not, create one. Refer to the [issue guidelines](#github-issue-guidelines).
1. Checkout the `develop` branch and pull the latest changes.
   ```
   git checkout develop
   git pull
   ```
1. Create a new branch with the naming scheme `issueId-description`.
   ```
   git checkout -b 123-adding-bpel-support`
   ```
1. Follow the [Google Java Format](https://github.com/google/google-java-format#intellij-android-studio-and-other-jetbrains-ides)
   and [Zeebe Code Style](https://github.com/zeebe-io/zeebe/wiki/Code-Style) while coding.
1. Implement the required changes on your branch and regularly push your
   changes to the origin so that the CI can run. Git commit will run a
   pre-commit hook which will check the formatting, style and license headers
   before committing. If these checks fail please fix the issues. Code format
   and license headers can be fixed automatically by running maven. Checkstyle
   violations have to be fixed manually.
   ```
   git commit -am 'feat(broker): bpel support'
   git push -u origin 123-adding-bpel-support
   ```
1. If you think you finished the issue please prepare the branch for reviewing.
   In general the commits should be squashed into meaningful commits with a
   helpful message. This means cleanup/fix etc commits should be squashed into
   the related commit. If you made refactorings or similar which are not
   directly necessary for the task it would be best if they are split up into
   another commit. Rule of thumb is that you should think about how a reviewer
   can best understand your changes. Please follow the [commit message
   guidelines](#commit-message-guidelines).
1. After finishing up the squashing force push your changes to your branch.
   ```
   git push --force-with-lease
   ```
1. To start the review process create a new pull request on GitHub from your
   branch to the `develop` branch. Give it a meaningful name and describe
   your changes in the body of the pull request. Lastly add a link to the issue
   this pull request closes, i.e. by writing in the description `closes #123`
1. Assign the pull request to one developer to review, if you are not sure who
   should review the issue skip this step. Someone will assign a reviewer for
   you.
1. The reviewer will look at the pull request in the following days and give
   you either feedback or accept the changes.
    1. If there are changes requested address them in a new commit. Notify the
       reviewer in a comment if the pull request is ready for review again. If
       the changes are accepted squash them again in the related commit and force push.
       Then initiate a merge by writing a comment with the contet `bors r+`.
    1. If no changes are requested the reviewer will initiate a merge by adding a
       comment with the content `bors r+`.
1. When a merge is initiated, a bot will merge your branch with the latest
   develop and run the CI on it.
    1. If everything goes well the branch is merged and deleted and the issue
       and pull request are cloesed.
    2. If there are merge conflicts the author of the pull request has to
       manually rebase `develop` into the issue branch and retrigger a merge
       attempt.
    3. If there are CI errors the author of the pull request has to check if
       they are caused by its changes and address them. If they are flaky tests
       a merge can be retried with a comment with the content `bors retry`.

## Commit Message Guidelines

Commit messages use [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/#summary) format with scope.

Examples:

* `docs(reference): add start event to bpmn symbol support matrix`
* `fix(broker): reduce latency in backpressure`
* `feat(clients/go): allow more than 9000 jobs in a single call`

The commit message should match the following pattern:
```
%{type}(%{scope}): %{description}
```

- `type` and `scope` should be chosen as follows
    - `feat`: For user facing features or improvements. `scope` should be either
      `broker`, `clients/java` or `clients/go`.
    - `fix`: For user facing bug fixes. `scope` should be either
      `broker`, `clients/java` or `clients/go`.
    - `chore`: For code changes which are not user facing, `scope` should be
      the folder name of the component which contains the main part of the
      change, e.g. `broker` or `exporters`.
    - `docs`:  For changes on the documentation. `scope` should be the sub folder
      name in the `docs/src/` directory which contains the main change, e.g.
      `introduction` or `reference`.
- `description`: short description of the change to a max length of the whole
  subject line of 120 characters


Zeebe uses a bot which will check your commit messages when a pull request is
submitted. Please make sure to address any hints from the bot.

## Contributor License Agreement

You will be asked to sign our Contributor License Agreement when you open a Pull Request. We are not
asking you to assign copyright to us, but to give us the right to distribute
your code without restriction. We ask this of all contributors in order to
assure our users of the origin and continuing existence of the code. You only
need to sign the CLA once.

## Licenses

Zeebe source files are made available under the [Zeebe Community License
Version 1.0](/licenses/ZEEBE-COMMUNITY-LICENSE-1.0.txt) except for the parts listed
below, which are made available under the [Apache License, Version
2.0](/licenses/APACHE-2.0.txt).  See individual source files for details.

Available under the [Apache License, Version 2.0](/licenses/APACHE-2.0.txt):
- Java Client ([clients/java](/clients/java))
- Go Client ([clients/go](/clients/go))
- Exporter API ([exporter-api](/exporter-api))
- Protocol ([protocol](/protocol))
- Gateway Protocol Implementation ([gateway-protocol-impl](/gateway-protocol-impl))
- BPMN Model API ([bpmn-model](/bpmn-model))

If you would like to contribute something, or simply want to hack on the code
this document should help you get started.

## Code of Conduct

This project adheres to the [Camunda Code of Conduct](https://camunda.com/events/code-conduct/).
By participating, you are expected to uphold this code. Please [report](https://camunda.com/events/code-conduct/reporting-violations/)
unacceptable behavior as soon as possible.

[issues]: https://github.com/zeebe-io/zeebe/issues
[forum]: https://forum.zeebe.io/
[slack]: https://zeebe-slack-invite.herokuapp.com/
[sample]: https://github.com/zeebe-io/zeebe-test-template-java

[status]: https://github.com/zeebe-io/zeebe/labels?q=Type
[planned]: https://github.com/zeebe-io/zeebe/labels/Type%3A%20Enhancement
[ready]: https://github.com/zeebe-io/zeebe/labels/Type%3A%20Maintenance
[in progress]: https://github.com/zeebe-io/zeebe/labels/Type%3A%20Bug
[needs review]: https://github.com/zeebe-io/zeebe/labels/Type%3A%20Docs

[type]: https://github.com/zeebe-io/zeebe/labels?q=Type
[enhancement]: https://github.com/zeebe-io/zeebe/labels/Type%3A%20Enhancement
[maintenance]: https://github.com/zeebe-io/zeebe/labels/Type%3A%20Maintenance
[bug]: https://github.com/zeebe-io/zeebe/labels/Type%3A%20Bug
[docs]: https://github.com/zeebe-io/zeebe/labels/Type%3A%20Docs
[question]: https://github.com/zeebe-io/zeebe/labels/Type%3A%20Question

[scope]: https://github.com/zeebe-io/zeebe/labels?q=Scope
[broker]: https://github.com/zeebe-io/zeebe/labels/Scope%3A%20broker
[clients/java]: https://github.com/zeebe-io/zeebe/labels/Scope%3A%20clients%2Fjava
[clients/go]: https://github.com/zeebe-io/zeebe/labels/Scope%3A%20clients%2Fgo
