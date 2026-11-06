package ezmj;

/** 執行全部測試；有失敗時以非零代碼結束，打包腳本會因此中止。 */
public final class AllTests {

    private AllTests() {
    }

    public static void main(String[] args) {
        TilesTest.run();
        HandAnalyzerTest.run();
        ScorerTest.run();
        RoundFlowTest.run();
        SessionTest.run();
        SessionTest.botNames();
        SessionTest.records();
        SessionTest.flowerWins();
        SessionTest.bigSettings();
        SettingsTest.run();
        HonorRollTest.run();
        AchievementTest.run();
        CareerTest.run();
        SealTest.run();
        SaveTest.run();
        RefereeTest.run();
        SimulationTest.run();
        BotTest.run();
        TableFlowTest.run();
        OnlineTest.run();
        WallRingTest.run();
        System.exit(T.report() == 0 ? 0 : 1);
    }
}
