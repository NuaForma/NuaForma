"""Research report-policy checks, independent of PDF renderer output."""
import pathlib
import tempfile
import unittest
from matrix import report


class ReportPolicyTest(unittest.TestCase):
    def parse(self, validation='', summary=''):
        with tempfile.TemporaryDirectory() as temporary:
            path = pathlib.Path(temporary) / "report.xml"
            path.write_text('<report><validationReport jobEndStatus="normal" profileName="PDF/A-3a validation profile" isCompliant="true" ' + validation + '><details passedRules="155" passedChecks="551" failedRules="0" failedChecks="0"/></validationReport><batchSummary totalJobs="1" failedToParse="0" encrypted="0" outOfMemory="0" veraExceptions="0" ' + summary + '><validationReports compliant="1" nonCompliant="0" failedJobs="0">1</validationReports></batchSummary></report>')
            return report(path)

    def test_complete_expected_report(self):
        self.assertTrue(self.parse()["compliant"])

    def test_missing_report_is_not_success(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = pathlib.Path(temporary) / "report.xml"
            path.write_text('<report><batchSummary totalJobs="1"/></report>')
            with self.assertRaises(AssertionError):
                report(path)

    def test_wrong_profile_aborted_and_exception_reports_are_not_success(self):
        valid = '<report><validationReport jobEndStatus="normal" profileName="PDF/A-3a validation profile" isCompliant="true"><details passedRules="155" passedChecks="551" failedRules="0" failedChecks="0"/></validationReport><batchSummary totalJobs="1" failedToParse="0" encrypted="0" outOfMemory="0" veraExceptions="0"><validationReports compliant="1" nonCompliant="0" failedJobs="0">1</validationReports></batchSummary></report>'
        for old, new in [('PDF/A-3a', 'PDF/A-3b'), ('jobEndStatus="normal"', 'jobEndStatus="validationError"'), ('outOfMemory="0"', 'outOfMemory="1"'), ('veraExceptions="0"', 'veraExceptions="1"'), ('totalJobs="1"', 'totalJobs="0"'), ('failedChecks="0"', 'failedChecks="1"'), ('passedChecks="551"', 'passedChecks="0"'), ('<details passedRules="155" passedChecks="551" failedRules="0" failedChecks="0"/>', '')]:
            with self.subTest(new=new), tempfile.TemporaryDirectory() as temporary:
                path = pathlib.Path(temporary) / "report.xml"
                path.write_text(valid.replace(old, new))
                with self.assertRaises(AssertionError):
                    report(path)


if __name__ == "__main__":
    unittest.main()
