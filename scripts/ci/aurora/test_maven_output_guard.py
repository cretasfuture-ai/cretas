import io
from pathlib import Path
import tempfile
import unittest
import maven_output_guard as g

class OutputGuardTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)
    def run_guard(self, data):
        out=io.BytesIO();rc=g.observe(io.BytesIO(data),out,self.root)
        self.assertEqual(data,out.getvalue());return rc
    def reports(self, failures=0, errors=0, baseline='# empty accepted baseline\n'):
        (self.root/'known-failing-tests.txt').write_text(baseline)
        p=self.root/'target/surefire-reports';p.mkdir(parents=True)
        child = '<failure/>' if failures else '<error/>' if errors else ''
        (p/'TEST-fixture.xml').write_text(f'<testsuite tests="1" failures="{failures}" errors="{errors}"><testcase classname="Fixture" name="x">{child}</testcase></testsuite>')
    def test_no_summary_preserves_binary_output(self):
        self.assertEqual(0,self.run_guard('normal 输出\n'.encode()))
    def test_zero_summary_needs_no_reports(self):
        self.assertEqual(0,self.run_guard(b'[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0\n'))
    def test_overwritten_failure_is_not_green(self):
        self.reports();self.assertEqual(86,self.run_guard(b'[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0\n[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0\n'))
    def test_overwritten_error_is_not_green(self):
        self.reports();self.assertEqual(86,self.run_guard(b'[WARNING] Tests run: 1, Failures: 0, Errors: 1, Skipped: 0\n'))
    def test_ansi_failure_is_seen(self):
        self.reports();self.assertEqual(86,self.run_guard(b'\x1b[31m[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0\x1b[0m\n'))
    def test_retained_red_with_zero_baseline_goes_to_ratchet(self):
        self.reports(failures=1);self.assertEqual(0,self.run_guard(b'[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0\n'))
    def test_missing_xml_fails_closed(self):
        self.assertEqual(86,self.run_guard(b'[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0\n'))
    def test_invalid_xml_fails_closed(self):
        self.reports();(self.root/'target/surefire-reports/TEST-fixture.xml').write_text('<invalid')
        self.assertEqual(86,self.run_guard(b'[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0\n'))
    def test_positive_baseline_cannot_accept_unknown_ignored_failure(self):
        self.reports(failures=1,baseline='some.accepted.failure\n')
        self.assertEqual(86,self.run_guard(b'[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0\n'))
    def test_child_failure_is_seen_even_if_root_counters_are_zero(self):
        self.reports();(self.root/'target/surefire-reports/TEST-fixture.xml').write_text('<testsuite><testcase classname="Fixture" name="x"><failure/></testcase></testsuite>')
        self.assertEqual(0,self.run_guard(b'[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0\n'))
    def test_root_counter_without_failed_testcase_fails_closed(self):
        self.reports();(self.root/'target/surefire-reports/TEST-fixture.xml').write_text('<testsuite failures="1"><testcase classname="Fixture" name="x"/></testsuite>')
        self.assertEqual(86,self.run_guard(b'[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0\n'))
    def test_anonymous_failure_is_not_accepted_by_ratchet(self):
        self.reports();(self.root/'target/surefire-reports/TEST-fixture.xml').write_text('<testsuite><testcase name="x"><failure/></testcase></testsuite>')
        self.assertEqual(86,self.run_guard(b'[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0\n'))
    def test_empty_normalized_name_is_not_accepted_by_ratchet(self):
        self.reports();(self.root/'target/surefire-reports/TEST-fixture.xml').write_text('<testsuite><testcase classname="Fixture" name=" [1]"><error/></testcase></testsuite>')
        self.assertEqual(86,self.run_guard(b'[ERROR] Tests run: 1, Failures: 0, Errors: 1, Skipped: 0\n'))
    def test_unprefixed_test_text_is_not_a_maven_summary(self):
        self.assertEqual(0,self.run_guard(b'example Tests run: 1, Failures: 1, Errors: 0\n'))

if __name__=='__main__':unittest.main()
