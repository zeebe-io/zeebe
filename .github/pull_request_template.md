## Description

<!-- Please explain the changes you made here. -->

## Related issues

<!-- Which issues are closed by this PR or are related -->

closes #

<!-- Cut-off marker
_All lines under and including the cut-off marker will be removed from the merge commit message_

## Definition of Ready

Please check the items that apply, before requesting a review.

You can find more details about these items in our wiki page about [Pull Requests and Code Reviews](https://github.com/camunda-cloud/zeebe/wiki/Pull-Requests-and-Code-Reviews).

* [ ] I've reviewed my own code
* [ ] I've written a clear changelist description
* [ ] I've narrowly scoped my changes
* [ ] I've separated structural from behavioural changes
-->

## Definition of Done

<!-- Please check the items that apply, before merging or (if possible) before requesting a review. -->

_Not all items need to be done depending on the issue and the pull request._

Code changes:
* [ ] The changes are backwards compatibility with previous versions
* [ ] If it fixes a bug then PRs are created to [backport](https://github.com/zeebe-io/zeebe/compare/stable/0.24...develop?expand=1&template=backport_template.md&title=[Backport%200.24]) the fix to the last two minor versions. You can trigger a backport by assigning labels (e.g. `backport stable/0.25`) to the PR, in case that fails you need to create backports manually.

Testing:
* [ ] There are unit/integration tests that verify all acceptance criterias of the issue
* [ ] New tests are written to ensure backwards compatibility with further versions
* [ ] The behavior is tested manually
* [ ] The change has been verified by a QA run
* [ ] The impact of the changes is verified by a benchmark 

Documentation: 
* [ ] The documentation is updated (e.g. BPMN reference, configuration, examples, get-started guides, etc.)
* [ ] New content is added to the [release announcement](https://drive.google.com/drive/u/0/folders/1DTIeswnEEq-NggJ25rm2BsDjcCQpDape)
