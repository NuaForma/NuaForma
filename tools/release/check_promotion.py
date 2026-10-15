"""Guard main's release-promotion route without publishing or accessing secrets."""
import os
import re


def check(base, head, repository):
    if base != 'main' or repository != 'NuaForma/NuaForma':
        raise ValueError('Release promotions require the internal product repository and main target')
    if head != 'develop' and not re.fullmatch(r'hotfix/[A-Za-z0-9][A-Za-z0-9._/-]*', head):
        raise ValueError('Promote develop or a deliberately named hotfix branch; features target develop')


if __name__ == '__main__':
    check(os.environ['BASE_BRANCH'], os.environ['HEAD_BRANCH'], os.environ['HEAD_REPOSITORY'])
    print('Release promotion source/target: PASS; no tag or artifact is published')
