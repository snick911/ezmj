package ezmj;

import static ezmj.T.check;
import static ezmj.T.eq;
import static ezmj.T.fails;
import static ezmj.T.test;
import static ezmj.T.tile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

import ezmj.bot.Bot;
import ezmj.bot.ComputerBot;
import ezmj.bot.SeatedBots;
import ezmj.engine.Claim;
import ezmj.engine.GameSession;
import ezmj.engine.Round;
import ezmj.engine.RoundEvent;
import ezmj.engine.Settlement;
import ezmj.engine.TurnOptions;
import ezmj.model.BotLevel;
import ezmj.model.GameConfig;
import ezmj.model.Meld;
import ezmj.table.TableFlow;

/** 牌桌流程：用假的時鐘走真正的流程，不開畫面。 */
final class TableFlowTest {

    /** 只聽 3w 的 16 張。 */
    private static final String WINS_ON_3W = "1w2w 4w5w6w 7p8p9p 2s3s4s 5s6s7s 9p9p";

    private static final Bot NEVER = new Bot() {
        @Override
        public void playTurn(Round round, int seat, TurnOptions options) {
            round.discard(seat, round.rightmostTile(seat));
        }

        @Override
        public Claim chooseClaim(Round round, int seat, List<Claim> options) {
            return null;
        }

        @Override
        public boolean declareListening(Round round, int seat) {
            return false;
        }
    };

    private TableFlowTest() {
    }

    /** 假時鐘：動作照時間順序執行，不用真的等。 */
    private static final class Clock implements TableFlow.Scheduler {

        private record Job(long at, long order, Runnable action, boolean[] cancelled) {
        }

        private final PriorityQueue<Job> jobs = new PriorityQueue<>(
                Comparator.comparingLong(Job::at).thenComparingLong(Job::order));
        private long now;
        private long order;

        @Override
        public Runnable after(int delayMs, Runnable action) {
            boolean[] cancelled = new boolean[1];
            jobs.add(new Job(now + delayMs, order++, action, cancelled));
            return () -> cancelled[0] = true;
        }

        void pass(long ms) {
            long end = now + ms;
            while (!jobs.isEmpty() && jobs.peek().at() <= end) {
                Job job = jobs.poll();
                now = job.at();
                if (!job.cancelled()[0]) {
                    job.action().run();
                }
            }
            now = end;
        }

        boolean idle() {
            return jobs.stream().allMatch(job -> job.cancelled()[0]);
        }
    }

    private record ClaimAsk(int id, List<Claim> options) {
    }

    /** 記下流程通知的事。 */
    private static class Recorder implements TableFlow.Listener {
        final List<String> logs = new ArrayList<>();
        final List<Integer> seconds = new ArrayList<>();
        final Map<Integer, Integer> turnAsks = new HashMap<>();
        final Map<Integer, ClaimAsk> claimAsks = new HashMap<>();
        int checkpoints;
        int finished;
        int listening;
        final Map<Integer, Integer> listenOffers = new HashMap<>();

        @Override
        public void log(String message) {
            logs.add(message);
        }

        @Override
        public void roundOpened(Round round) {
        }

        @Override
        public int dealt(Round round) {
            return 0;
        }

        @Override
        public void event(RoundEvent event) {
            if (event.type() == RoundEvent.Type.LISTEN) {
                listening++;
            }
        }

        @Override
        public void refresh(Round round) {
        }

        @Override
        public void idle(int seat) {
        }

        @Override
        public void checkpoint() {
            checkpoints++;
        }

        @Override
        public void askTurn(int seat, int waitId, TurnOptions options) {
            turnAsks.put(seat, waitId);
        }

        @Override
        public void askClaim(int seat, int waitId, List<Claim> options) {
            claimAsks.put(seat, new ClaimAsk(waitId, options));
        }

        @Override
        public void secondsLeft(int value) {
            seconds.add(value);
        }

        @Override
        public void canListen(int seat, int listenId) {
            listenOffers.put(seat, listenId);
        }

        @Override
        public void thinkingFinished(int seat) {
        }

        @Override
        public void roundFinished(Round round, Settlement settlement) {
            finished++;
        }
    }

    private static TableFlow.Pacing pacing(int waitSeconds) {
        return new TableFlow.Pacing() {
            @Override
            public int turnDelayMs() {
                return 100;
            }

            @Override
            public int claimDelayMs() {
                return 50;
            }

            @Override
            public int waitSeconds() {
                return waitSeconds;
            }
        };
    }

    private static GameSession session(long seed) {
        return new GameSession(new GameConfig("測試", 100_000, 100, 20, BotLevel.NORMAL), new Random(seed));
    }

    /** 接到排好牌的一局上。 */
    private static TableFlow flow(Set<Integer> people, Bot proxy, int waitSeconds, Clock clock, Recorder recorder) {
        return new TableFlow(session(1), people, NEVER, proxy, pacing(waitSeconds), clock, recorder);
    }

    private static Claim option(List<Claim> options, Claim.Type type) {
        return options.stream().filter(claim -> claim.type() == type).findFirst()
                .orElseThrow(() -> new AssertionError("沒有 " + type));
    }

    /** 座位 1、2 都能對 1w 宣告：1 能吃、2 能碰。莊家 0 剛摸到 1w。 */
    private static Round chowAndPong() {
        return Table.dealer(0).hand(1, "2w3w").hand(2, "1w1w").draws("1w").start();
    }

    static void run() {
        T.section("牌桌流程");
        waiting();
        timeouts();
        manyPeople();
        staleAndIllegal();
        automation();
        claimRace();
        kongs();
        thinking();
        stopping();
        sameGameAsBefore();
    }

    private static void waiting() {
        test("輪到真人就一直等，電腦照節奏動作", () -> {
            Clock clock = new Clock();
            Recorder recorder = new Recorder();
            TableFlow flow = flow(Set.of(1), NEVER, 0, clock, recorder);
            Round round = Table.dealer(0).draws("9w").start();
            flow.resume(round);
            eq(Round.Phase.TURN, round.phase(), "電腦還沒動作");
            eq(0, round.current(), "輪到莊家");
            clock.pass(99);
            eq(Round.Phase.TURN, round.phase(), "還沒到時間");
            clock.pass(1);
            clock.pass(50);
            eq(1, round.current(), "輪到真人");
            check(recorder.turnAsks.containsKey(1), "要問真人");
            clock.pass(1_000_000);
            eq(1, round.current(), "不限時就一直等");
            eq(List.of(0), recorder.seconds, "不限時不用倒數");
            eq(1, recorder.checkpoints, "等真人前要存檔");
            flow.discard(1, recorder.turnAsks.get(1), round.rightmostTile(1));
            eq(Round.Phase.CLAIMS, round.phase(), "打完等宣告");
        });

        test("開局的摸牌與補花不通知，之後的才通知", () -> {
            Clock clock = new Clock();
            List<RoundEvent.Type> events = new ArrayList<>();
            Recorder recorder = new Recorder() {
                @Override
                public void event(RoundEvent event) {
                    events.add(event.type());
                }
            };
            TableFlow flow = new TableFlow(session(3), Set.of(0, 1, 2, 3), NEVER, NEVER, pacing(0), clock, recorder);
            flow.startRound();
            check(events.stream().noneMatch(type -> type == RoundEvent.Type.DRAW || type == RoundEvent.Type.FLOWER),
                    "開局不通知: " + events);
            int seat = flow.round().current();
            flow.discard(seat, recorder.turnAsks.get(seat), flow.round().rightmostTile(seat));
            check(events.contains(RoundEvent.Type.DISCARD), "打牌要通知");
        });
    }

    private static void timeouts() {
        test("輪到自己時間到：打出最右邊的牌", () -> {
            Clock clock = new Clock();
            Recorder recorder = new Recorder();
            TableFlow flow = flow(Set.of(0), NEVER, 3, clock, recorder);
            Round round = Table.dealer(0).draws("9w").start();
            flow.resume(round);
            clock.pass(2999);
            eq(List.of(3, 2, 1), recorder.seconds, "每秒通知");
            eq(Round.Phase.TURN, round.phase(), "還沒到");
            clock.pass(1);
            eq(tile("9w"), round.player(0).discards().get(0), "打出剛摸的牌");
        });

        test("宣告時間到：還沒決定的都算過，已決定的照算", () -> {
            Clock clock = new Clock();
            Recorder recorder = new Recorder();
            TableFlow flow = flow(Set.of(0, 1, 2), NEVER, 2, clock, recorder);
            Round round = chowAndPong();
            flow.resume(round);
            flow.discard(0, recorder.turnAsks.get(0), tile("1w"));
            ClaimAsk chow = recorder.claimAsks.get(1);
            flow.answerClaim(1, chow.id(), option(chow.options(), Claim.Type.CHOW));
            eq(Round.Phase.CLAIMS, round.phase(), "碰的那家還沒決定");
            clock.pass(2000);
            eq(1, round.current(), "碰的那家算過，吃成立");
            eq(1, round.player(1).melds().size(), "有吃到");
        });

        test("改等待時間會從頭計時", () -> {
            Clock clock = new Clock();
            Recorder recorder = new Recorder();
            int[] wait = {2};
            TableFlow.Pacing pacing = new TableFlow.Pacing() {
                @Override
                public int turnDelayMs() {
                    return 0;
                }

                @Override
                public int claimDelayMs() {
                    return 0;
                }

                @Override
                public int waitSeconds() {
                    return wait[0];
                }
            };
            TableFlow flow = new TableFlow(session(1), Set.of(0), NEVER, NEVER, pacing, clock, recorder);
            Round round = Table.dealer(0).draws("9w").start();
            flow.resume(round);
            clock.pass(1500);
            wait[0] = 10;
            flow.restartCountdown();
            clock.pass(9000);
            eq(0, round.player(0).discards().size(), "重新算的 10 秒還沒到");
            clock.pass(1000);
            eq(1, round.player(0).discards().size(), "10 秒到了");
        });
    }

    private static void manyPeople() {
        test("多位真人宣告：都決定才處理，依優先序", () -> {
            Clock clock = new Clock();
            Recorder recorder = new Recorder();
            TableFlow flow = flow(Set.of(0, 1, 2), NEVER, 0, clock, recorder);
            Round round = chowAndPong();
            flow.resume(round);
            flow.discard(0, recorder.turnAsks.get(0), tile("1w"));
            ClaimAsk chow = recorder.claimAsks.get(1);
            ClaimAsk pong = recorder.claimAsks.get(2);
            eq(chow.id(), pong.id(), "同一次等待");
            flow.answerClaim(1, chow.id(), option(chow.options(), Claim.Type.CHOW));
            eq(Round.Phase.CLAIMS, round.phase(), "還在等另一家");
            flow.answerClaim(1, chow.id(), null);
            flow.answerClaim(2, pong.id(), option(pong.options(), Claim.Type.PONG));
            eq(2, round.current(), "碰優先於吃，同一家不能改答案");
            eq(0, round.player(1).melds().size(), "吃不成立");
            eq(1, round.player(2).melds().size(), "碰成立");
        });

        test("多位真人都過：輪到下一家", () -> {
            Clock clock = new Clock();
            Recorder recorder = new Recorder();
            TableFlow flow = flow(Set.of(0, 1, 2), NEVER, 0, clock, recorder);
            Round round = chowAndPong();
            flow.resume(round);
            flow.discard(0, recorder.turnAsks.get(0), tile("1w"));
            flow.answerClaim(2, recorder.claimAsks.get(2).id(), null);
            flow.answerClaim(1, recorder.claimAsks.get(1).id(), null);
            eq(Round.Phase.TURN, round.phase(), "摸牌了");
            eq(1, round.current(), "輪到下家");
        });
    }

    private static void staleAndIllegal() {
        test("舊的決定和不是問你的決定都不算", () -> {
            Clock clock = new Clock();
            Recorder recorder = new Recorder();
            TableFlow flow = flow(Set.of(0, 1), NEVER, 0, clock, recorder);
            Round round = Table.dealer(0).draws("9w").backs("1z").start();
            flow.resume(round);
            int first = recorder.turnAsks.get(0);
            flow.discard(1, first, round.rightmostTile(0));
            eq(Round.Phase.TURN, round.phase(), "不是問座位 1");
            flow.discard(0, first + 1, round.rightmostTile(0));
            eq(Round.Phase.TURN, round.phase(), "編號不對");
            check(flow.isWaiting(0, first), "還在等");
            flow.discard(0, first, round.rightmostTile(0));
            check(!flow.isWaiting(0, first), "決定之後就不等了");
            clock.pass(50);
            eq(1, round.current(), "輪到座位 1");
            flow.discard(0, first, round.rightmostTile(1));
            eq(1, round.current(), "同一個編號不能再用一次");
        });

        test("不合規則的決定丟例外，局面不變，還可以重新決定", () -> {
            Clock clock = new Clock();
            Recorder recorder = new Recorder();
            TableFlow flow = flow(Set.of(0, 1, 2), NEVER, 0, clock, recorder);
            Round round = chowAndPong();
            flow.resume(round);
            int turn = recorder.turnAsks.get(0);
            fails(IllegalArgumentException.class, () -> flow.discard(0, turn, tile("9w")), "手上沒有的牌");
            fails(IllegalArgumentException.class, () -> flow.declareListening(0, turn, tile("1w")), "打了不會聽牌");
            fails(IllegalStateException.class, () -> flow.declareSelfWin(0, turn), "不能自摸");
            fails(IllegalStateException.class, () -> flow.declareKong(0, turn, tile("1w")), "不能槓");
            check(flow.isWaiting(0, turn), "還在等");
            flow.discard(0, turn, tile("1w"));

            ClaimAsk chow = recorder.claimAsks.get(1);
            ClaimAsk pong = recorder.claimAsks.get(2);
            fails(IllegalArgumentException.class,
                    () -> flow.answerClaim(1, chow.id(), option(pong.options(), Claim.Type.PONG)), "座位 1 不能碰");
            check(flow.isWaiting(1, chow.id()), "還在等座位 1");
            flow.answerClaim(1, chow.id(), null);
            flow.answerClaim(2, pong.id(), null);
            eq(Round.Phase.TURN, round.phase(), "局面照常往下走");
        });
    }

    private static void automation() {
        Bot takesAll = new Bot() {
            @Override
            public void playTurn(Round round, int seat, TurnOptions options) {
                round.discard(seat, round.rightmostTile(seat));
            }

            @Override
            public Claim chooseClaim(Round round, int seat, List<Claim> options) {
                return options.get(0);
            }

            @Override
            public boolean declareListening(Round round, int seat) {
                return false;
            }
        };

        test("打開代打：輪到自己時照節奏交給電腦", () -> {
            Clock clock = new Clock();
            Recorder recorder = new Recorder();
            TableFlow flow = flow(Set.of(0), takesAll, 0, clock, recorder);
            Round round = Table.dealer(0).draws("9w").start();
            flow.resume(round);
            flow.setAutoPlay(0, true);
            check(!flow.isWaiting(0, recorder.turnAsks.get(0)), "不再等真人");
            eq(Round.Phase.TURN, round.phase(), "還在停頓");
            clock.pass(100);
            eq(Round.Phase.CLAIMS, round.phase(), "電腦打了");
            eq(0, flow.thinking(0).count(), "代打那次不算思考");
        });

        test("等待代打的停頓裡關掉，就還給真人", () -> {
            Clock clock = new Clock();
            Recorder recorder = new Recorder();
            TableFlow flow = flow(Set.of(0), takesAll, 0, clock, recorder);
            Round round = Table.dealer(0).draws("9w").start();
            flow.resume(round);
            int first = recorder.turnAsks.get(0);
            flow.setAutoPlay(0, true);
            flow.setAutoPlay(0, false);
            clock.pass(100);
            eq(Round.Phase.TURN, round.phase(), "電腦沒有打");
            check(recorder.turnAsks.get(0) != first, "重新問真人");
        });

        test("宣告中打開代打：只決定自己，其他真人繼續等", () -> {
            Clock clock = new Clock();
            Recorder recorder = new Recorder();
            TableFlow flow = flow(Set.of(0, 1, 2), takesAll, 0, clock, recorder);
            Round round = chowAndPong();
            flow.resume(round);
            flow.discard(0, recorder.turnAsks.get(0), tile("1w"));
            ClaimAsk pong = recorder.claimAsks.get(2);
            flow.setAutoPlay(1, true);
            eq(Round.Phase.CLAIMS, round.phase(), "還在等座位 2");
            check(flow.isWaiting(2, pong.id()), "座位 2 的等待還算數");
            flow.answerClaim(2, pong.id(), null);
            eq(1, round.current(), "代打替座位 1 吃了");
            eq(1, round.player(1).melds().size(), "有吃到");
        });

        test("只等一家宣告時打開代打：照停頓才處理", () -> {
            Clock clock = new Clock();
            Recorder recorder = new Recorder();
            TableFlow flow = flow(Set.of(0, 1), takesAll, 0, clock, recorder);
            Round round = chowAndPong();
            flow.resume(round);
            flow.discard(0, recorder.turnAsks.get(0), tile("1w"));
            flow.setAutoPlay(1, true);
            eq(Round.Phase.CLAIMS, round.phase(), "還在停頓");
            clock.pass(50);
            eq(1, round.current(), "代打替座位 1 吃了");
        });

        test("打出並報聽（單機的聽牌按鈕、舊的連線客戶端）：摸什麼打什麼，只胡不吃碰，胡了就結束", () -> {
            Clock clock = new Clock();
            Recorder recorder = new Recorder();
            TableFlow flow = flow(Set.of(1), NEVER, 0, clock, recorder);
            // 座位 1 聽 3w；座位 0 先打 9w，座位 1 打掉摸到的 2z 聽牌，座位 2 打 1w 不碰，座位 3 打 3w 放槍
            Round round = Table.dealer(0).hand(1, WINS_ON_3W).draws("9w 2z 1w 3w").start();
            flow.resume(round);
            clock.pass(150);
            eq(1, round.current(), "輪到座位 1");
            flow.declareListening(1, recorder.turnAsks.get(1), tile("2z"));
            eq(1, recorder.listening, "通知聽牌");
            eq(T.hand("3w"), flow.listeningWaits(1), "聽 3w");
            clock.pass(10_000);
            eq(Round.Phase.FINISHED, round.phase(), "胡了");
            eq(1, round.result().winner(), "座位 1 胡");
            eq(1, recorder.finished, "通知結束一次");
            eq(1, recorder.turnAsks.size(), "聽牌之後不再問");
            eq(0, recorder.claimAsks.size(), "聽牌之後也不問宣告");
        });

        test("報聽：打完聽牌才問，按了就公開；摸到胡的牌就自摸，多算 1 台；開新局就解除", () -> {
            Clock clock = new Clock();
            Recorder recorder = new Recorder();
            TableFlow flow = flow(Set.of(1), NEVER, 0, clock, recorder);
            // 座位 1 摸到 2z 打掉就聽 3w，之後每家打剛摸的牌，座位 1 再摸到 3w 自摸
            Round round = Table.dealer(0).hand(1, WINS_ON_3W).draws("9w 2z 1w 1p 2p 3w").start();
            flow.resume(round);
            clock.pass(150);
            check(!recorder.listenOffers.containsKey(1), "還沒打牌不問");
            flow.discard(1, recorder.turnAsks.get(1), tile("2z"));
            int offer = recorder.listenOffers.get(1);
            check(round.canDeclareListening(1), "可以報聽");
            eq(offer, flow.listenOffer(1), "重連時補送用");
            flow.declareListening(1, offer + 1);
            check(!round.isListening(1), "編號不對就沒有作用");
            flow.declareListening(1, offer);
            check(round.isListening(1), "報聽了");
            eq(1, recorder.listening, "全桌都知道");
            eq(0, flow.listenOffer(1), "報過就沒有機會了");
            eq(T.hand("3w"), flow.listeningWaits(1), "聽 3w");
            clock.pass(10_000);
            eq(1, round.result().winner(), "自摸");
            check(round.result().isSelfDraw(), "是自摸");
            check(round.result().score().items().stream().anyMatch(item -> item.name().equals("聽牌") && item.tai() == 1),
                    "多算 1 台：" + round.result().score().items());
            eq(1, recorder.turnAsks.size(), "報聽之後不再問");
            flow.startRound();
            check(flow.listeningWaits(1) == null, "新的一局沒有報聽");
        });

        test("報聽的機會：一直到下一次摸牌，放棄別家的牌不影響", () -> {
            Clock clock = new Clock();
            Recorder recorder = new Recorder();
            TableFlow flow = flow(Set.of(1), NEVER, 0, clock, recorder);
            Round round = Table.dealer(0).hand(1, WINS_ON_3W).draws("9w 2z 1w 1p 2p 4p").start();
            flow.resume(round);
            clock.pass(150);
            flow.discard(1, recorder.turnAsks.get(1), tile("2z"));
            int offer = recorder.listenOffers.get(1);
            clock.pass(300);
            eq(2, round.player(2).discards().size() + round.player(3).discards().size(), "別家各打了一張，座位 1 都放棄");
            check(round.canDeclareListening(1), "還可以報");
            clock.pass(10_000);
            eq(1, round.current(), "又輪到座位 1 摸牌");
            eq(Round.Phase.TURN, round.phase(), "等座位 1 打牌");
            check(!round.canDeclareListening(1), "摸牌之後就沒有了");
            flow.declareListening(1, offer);
            check(!round.isListening(1), "過期的編號沒有作用");
        });

        test("被問要不要碰的時候報聽：改成自動，能胡才胡，這次不吃碰", () -> {
            Clock clock = new Clock();
            Recorder recorder = new Recorder();
            TableFlow flow = flow(Set.of(1), NEVER, 0, clock, recorder);
            // 座位 1 打 2z 聽 3w，手上有兩張 9p；座位 2 打 9p，座位 1 可以碰
            Round round = Table.dealer(0).hand(1, WINS_ON_3W).draws("9w 2z 9p").start();
            flow.resume(round);
            clock.pass(150);
            flow.discard(1, recorder.turnAsks.get(1), tile("2z"));
            int offer = recorder.listenOffers.get(1);
            clock.pass(50 + 100);
            ClaimAsk pong = recorder.claimAsks.get(1);
            check(flow.isWaiting(1, pong.id()), "問座位 1 要不要碰");
            flow.declareListening(1, offer);
            check(!flow.isWaiting(1, pong.id()), "不再等他決定");
            clock.pass(50);
            eq(3, round.current(), "沒有碰，下一家摸牌");
            eq(0, round.player(1).melds().size(), "沒有面子");
        });

        test("電腦打完牌聽牌了，自己決定要不要報聽，報了之後摸什麼打什麼", () -> {
            Clock clock = new Clock();
            Recorder recorder = new Recorder();
            Bot listens = new Bot() {
                @Override
                public void playTurn(Round round, int seat, TurnOptions options) {
                    round.discard(seat, round.rightmostTile(seat));
                }

                @Override
                public Claim chooseClaim(Round round, int seat, List<Claim> options) {
                    return null;
                }

                @Override
                public boolean declareListening(Round round, int seat) {
                    return true;
                }
            };
            TableFlow flow = new TableFlow(session(1), Set.of(0), listens, NEVER, pacing(0), clock, recorder);
            Round round = Table.dealer(0).hand(1, WINS_ON_3W).draws("9w 2z").start();
            flow.resume(round);
            flow.discard(0, recorder.turnAsks.get(0), tile("9w"));
            clock.pass(50 + 100);
            check(round.isListening(1), "電腦報聽了");
            eq(1, recorder.listening, "全桌都知道");
            check(!recorder.listenOffers.containsKey(1), "不用問電腦");
        });
    }

    private static void claimRace() {
        Bot pongs = new Bot() {
            @Override
            public void playTurn(Round round, int seat, TurnOptions options) {
                round.discard(seat, round.rightmostTile(seat));
            }

            @Override
            public Claim chooseClaim(Round round, int seat, List<Claim> options) {
                return options.stream().filter(claim -> claim.type() != Claim.Type.CHOW).findFirst().orElse(null);
            }

            @Override
            public boolean declareListening(Round round, int seat) {
                return false;
            }
        };

        test("電腦要碰、真人只能吃：等 2 秒就先碰，不等真人", () -> {
            Clock clock = new Clock();
            Recorder recorder = new Recorder();
            TableFlow flow = new TableFlow(session(1), Set.of(0, 1), pongs, NEVER, pacing(0), clock, recorder);
            Round round = chowAndPong();
            flow.resume(round);
            flow.discard(0, recorder.turnAsks.get(0), tile("1w"));
            ClaimAsk chow = recorder.claimAsks.get(1);
            clock.pass(TableFlow.BOT_CLAIM_MS - 1);
            check(flow.isWaiting(1, chow.id()), "2 秒內還在等真人");
            clock.pass(1);
            check(!flow.isWaiting(1, chow.id()), "被碰走了，不再等");
            eq(2, round.current(), "座位 2 碰了");
            eq(Meld.Type.PONG, round.player(2).melds().get(0).type(), "碰");
            eq(0, flow.thinking(1).count(), "被搶先的那次不算思考");
            flow.answerClaim(1, chow.id(), option(chow.options(), Claim.Type.CHOW));
            eq(0, round.player(1).melds().size(), "過期的決定沒有作用");
        });

        test("真人能胡：電腦要碰也照舊等真人", () -> {
            Clock clock = new Clock();
            Recorder recorder = new Recorder();
            TableFlow flow = new TableFlow(session(1), Set.of(0, 1), pongs, NEVER, pacing(0), clock, recorder);
            // 座位 1 胡 1w 也能吃，座位 2 能碰
            Round round = Table.dealer(0).hand(1, "2w3w 4w5w6w 7p8p9p 2s3s4s 5s6s7s 9p9p").hand(2, "1w1w").draws("1w").start();
            flow.resume(round);
            flow.discard(0, recorder.turnAsks.get(0), tile("1w"));
            ClaimAsk ask = recorder.claimAsks.get(1);
            clock.pass(10_000);
            check(flow.isWaiting(1, ask.id()), "還在等真人決定要不要胡");
            flow.answerClaim(1, ask.id(), null);
            eq(2, round.current(), "真人放棄，電腦碰");
        });

        test("真人對真人：一位決定碰，只能吃的那位就不用等", () -> {
            Clock clock = new Clock();
            Recorder recorder = new Recorder();
            TableFlow flow = flow(Set.of(0, 1, 2), NEVER, 0, clock, recorder);
            Round round = chowAndPong();
            flow.resume(round);
            flow.discard(0, recorder.turnAsks.get(0), tile("1w"));
            ClaimAsk chow = recorder.claimAsks.get(1);
            ClaimAsk pong = recorder.claimAsks.get(2);
            flow.answerClaim(2, pong.id(), option(pong.options(), Claim.Type.PONG));
            check(!flow.isWaiting(1, chow.id()), "不等座位 1");
            eq(2, round.current(), "座位 2 碰了");
        });

        test("真人很快決定吃，電腦要碰：真人都決定了就處理，碰比吃大", () -> {
            Clock clock = new Clock();
            Recorder recorder = new Recorder();
            TableFlow flow = new TableFlow(session(1), Set.of(0, 1), pongs, NEVER, pacing(0), clock, recorder);
            Round round = chowAndPong();
            flow.resume(round);
            flow.discard(0, recorder.turnAsks.get(0), tile("1w"));
            ClaimAsk chow = recorder.claimAsks.get(1);
            flow.answerClaim(1, chow.id(), option(chow.options(), Claim.Type.CHOW));
            eq(2, round.current(), "不用等 2 秒");
            eq(0, round.player(1).melds().size(), "沒吃到");
        });
    }

    private static void kongs() {
        test("暗槓：補牌之後還是輪到自己", () -> {
            Clock clock = new Clock();
            Recorder recorder = new Recorder();
            TableFlow flow = flow(Set.of(0), NEVER, 0, clock, recorder);
            Round round = Table.dealer(0).hand(0, "1w1w1w").draws("1w").backs("2w").start();
            flow.resume(round);
            int first = recorder.turnAsks.get(0);
            flow.declareKong(0, first, tile("1w"));
            eq(Meld.Type.CONCEALED_KONG, round.player(0).melds().get(0).type(), "暗槓");
            eq(Round.Phase.TURN, round.phase(), "補完牌還是自己");
            check(recorder.turnAsks.get(0) != first, "重新問一次");
            check(flow.thinking(0).isRunning(), "打出去才算想完");
        });

        test("加槓：先問別家搶槓，沒人搶才補牌", () -> {
            Clock clock = new Clock();
            Recorder recorder = new Recorder();
            TableFlow flow = flow(Set.of(0), NEVER, 0, clock, recorder);
            // 莊家座位 3 打 1z 給座位 0 碰，轉一圈後座位 0 摸到第四張 1z 加槓
            Round round = Table.dealer(3).hand(0, "1z1z").draws("1z 9w 8w 7w 1z").backs("2w").start();
            flow.resume(round);
            clock.pass(100);
            ClaimAsk pong = recorder.claimAsks.get(0);
            flow.answerClaim(0, pong.id(), option(pong.options(), Claim.Type.PONG));
            flow.discard(0, recorder.turnAsks.get(0), round.rightmostTile(0));
            clock.pass(1000);
            eq(0, round.current(), "又輪到座位 0");
            eq(tile("1z"), round.drawn(), "摸到第四張");
            flow.declareKong(0, recorder.turnAsks.get(0), tile("1z"));
            check(round.isRobbing(), "等別家搶槓");
            check(flow.thinking(0).isRunning(), "加槓還沒想完");
            clock.pass(50);
            eq(Meld.Type.OPEN_KONG, round.player(0).melds().get(0).type(), "沒人搶就成槓");
            eq(Round.Phase.TURN, round.phase(), "補牌");
            eq(0, round.current(), "還是自己");
        });
    }

    private static void thinking() {
        test("思考時間：每位真人分開算，吃碰後一路算到打出去", () -> {
            Clock clock = new Clock();
            Recorder recorder = new Recorder();
            TableFlow flow = flow(Set.of(0, 1, 2), NEVER, 0, clock, recorder);
            Round round = chowAndPong();
            flow.resume(round);
            flow.discard(0, recorder.turnAsks.get(0), tile("1w"));
            eq(1, flow.thinking(0).count(), "打完算一次");
            ClaimAsk chow = recorder.claimAsks.get(1);
            ClaimAsk pong = recorder.claimAsks.get(2);
            check(flow.thinking(1).isRunning() && flow.thinking(2).isRunning(), "兩位都在想");
            flow.answerClaim(1, chow.id(), null);
            eq(1, flow.thinking(1).count(), "過牌就算想完");
            flow.answerClaim(2, pong.id(), option(pong.options(), Claim.Type.PONG));
            eq(0, flow.thinking(2).count(), "碰了還沒打");
            check(flow.thinking(2).isRunning(), "還在算");
            flow.discard(2, recorder.turnAsks.get(2), round.rightmostTile(2));
            eq(1, flow.thinking(2).count(), "打出去才算一次");
        });
    }

    private static void stopping() {
        test("停掉之後什麼都不會再發生", () -> {
            Clock clock = new Clock();
            Recorder recorder = new Recorder();
            TableFlow flow = flow(Set.of(1), NEVER, 3, clock, recorder);
            Round round = Table.dealer(0).draws("9w").start();
            flow.resume(round);
            flow.stop();
            clock.pass(10_000);
            eq(Round.Phase.TURN, round.phase(), "電腦沒動作");
            eq(0, round.current(), "還是莊家");

            Clock prompted = new Clock();
            Recorder asked = new Recorder();
            TableFlow waitingFlow = flow(Set.of(0), NEVER, 3, prompted, asked);
            Round other = Table.dealer(0).draws("9w").start();
            waitingFlow.resume(other);
            waitingFlow.stop();
            prompted.pass(10_000);
            eq(Round.Phase.TURN, other.phase(), "倒數也停了");
            check(prompted.idle(), "沒有留下要做的事");
        });
    }

    /**
     * 單機版改用牌桌流程之後，同一個種子要打出一模一樣的牌局。
     * walkthrough 是抽出來之前 GameController 走的順序，代打接手真人的座位。
     */
    private static void sameGameAsBefore() {
        test("同一個種子，整場打法和抽出流程之前一樣", () -> {
            for (long seed = 1; seed <= 20; seed++) {
                eq(walkthrough(seed), viaFlow(seed), "種子 " + seed);
            }
        });
    }

    private static Bot seated(GameSession session, long seed) {
        GameConfig config = session.config();
        return new SeatedBots(config.botLevels(), List.of(session.personality(1), session.personality(2), session.personality(3)),
                config.basePay(), config.taiPay(), new Random(seed + 1));
    }

    private static Bot proxy(GameSession session, long seed) {
        GameConfig config = session.config();
        return new ComputerBot(BotLevel.PRO, config.basePay(), config.taiPay(), new Random(seed + 2));
    }

    private static List<String> walkthrough(long seed) {
        GameSession session = session(seed);
        Bot bot = seated(session, seed);
        Bot proxy = proxy(session, seed);
        List<String> logs = new ArrayList<>();
        while (!session.isOver()) {
            Round round = session.newRound(logs::add);
            round.start();
            while (round.phase() != Round.Phase.FINISHED) {
                if (round.phase() == Round.Phase.TURN) {
                    int seat = round.current();
                    Bot player = seat == 0 ? proxy : bot;
                    TurnOptions options = round.turnOptions(seat);
                    if (round.isListening(seat) && options.canSelfWin()) {
                        round.declareSelfWin(seat);
                    } else if (round.isListening(seat)) {
                        round.discard(seat, round.rightmostTile(seat));
                    } else {
                        player.playTurn(round, seat, options);
                    }
                    // 打完牌聽牌了，電腦自己決定要不要報聽
                    if (round.canDeclareListening(seat) && player.declareListening(round, seat)) {
                        round.declareListening(seat);
                    }
                } else {
                    Map<Integer, Claim> decisions = new HashMap<>();
                    for (int seat = 1; seat < 4; seat++) {
                        List<Claim> options = round.claimOptions(seat);
                        if (!options.isEmpty()) {
                            Claim claim = bot.chooseClaim(round, seat, options);
                            if (claim != null) {
                                decisions.put(seat, claim);
                            }
                        }
                    }
                    List<Claim> options = round.claimOptions(0);
                    if (!options.isEmpty()) {
                        Claim claim = proxy.chooseClaim(round, 0, options);
                        if (claim != null) {
                            decisions.put(0, claim);
                        }
                    }
                    round.resolveClaims(decisions);
                }
            }
            logs.add("結算 " + Arrays.toString(session.settle(round.result()).deltas()));
        }
        return logs;
    }

    private static List<String> viaFlow(long seed) {
        GameSession session = session(seed);
        Clock clock = new Clock();
        List<TableFlow> holder = new ArrayList<>();
        Recorder recorder = new Recorder() {
            @Override
            public void roundFinished(Round round, Settlement settlement) {
                logs.add("結算 " + Arrays.toString(settlement.deltas()));
                if (!session.isOver()) {
                    holder.get(0).startRound();
                }
            }
        };
        TableFlow flow = new TableFlow(session, Set.of(0), seated(session, seed), proxy(session, seed), pacing(0), clock, recorder);
        holder.add(flow);
        flow.setAutoPlay(0, true);
        flow.startRound();
        while (!clock.idle()) {
            clock.pass(1000);
        }
        return recorder.logs;
    }
}
