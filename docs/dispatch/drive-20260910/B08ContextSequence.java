import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/** Runs the actual three CI test classes in order, sharing one JVM/context cache. */
public class B08ContextSequence {
  public static void main(String[] args) {
    long failures=0;
    var launcher=LauncherFactory.create();
    for(String name : new String[]{
      "com.cretas.aims.service.workflow.ProductProcessWorkflowRuntimeTransactionTest",
      "com.cretas.aims.service.shortage.SalesOrderFinanceApprovedFanOutIntegrationTest",
      "com.cretas.aims.service.inventory.FgReservationLedgerServiceTest"}) {
      var listener=new SummaryGeneratingListener();
      launcher.execute(LauncherDiscoveryRequestBuilder.request().selectors(selectClass(name)).build(),listener);
      var result=listener.getSummary();
      System.out.println("B08_SEQUENCE class="+name+" found="+result.getTestsFoundCount()
        +" succeeded="+result.getTestsSucceededCount()+" failed="+result.getTestsFailedCount()
        +" skipped="+result.getTestsSkippedCount());
      result.getFailures().forEach(f -> System.out.println("B08_FAILURE "+f.getTestIdentifier().getDisplayName()+" "+f.getException().getMessage()));
      failures+=result.getTotalFailureCount();
      if(result.getTestsFoundCount()==0)failures++;
    }
    System.out.println("B08_SEQUENCE_TOTAL failures="+failures);
    System.exit(failures==0?0:1);
  }
}
