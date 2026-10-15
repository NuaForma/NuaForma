"""Feature contributions cannot skip develop to enter the release branch."""
import unittest
from check_promotion import check


class PromotionTest(unittest.TestCase):
    def test_internal_develop_and_deliberate_hotfix(self):
        for head in ('develop', 'hotfix/0.2.1-cancellation'):
            check('main', head, 'NuaForma/NuaForma')

    def test_wrong_branch_repository_or_target(self):
        for base, head, repository in (
            ('main', 'codex/88-work', 'NuaForma/NuaForma'),
            ('main', 'develop', 'other/NuaForma'),
            ('develop', 'develop', 'NuaForma/NuaForma'),
            ('main', 'hotfix/', 'NuaForma/NuaForma'),
            ('main', 'main', 'NuaForma/NuaForma')):
            with self.subTest(base=base, head=head, repository=repository), self.assertRaises(ValueError):
                check(base, head, repository)
