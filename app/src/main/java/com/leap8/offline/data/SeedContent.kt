package com.leap8.offline.data

import com.leap8.offline.domain.*

object SeedContent {
    val vocabulary = listOf(
        VocabularyCard("v001", "achieve", "/əˈtʃiːv/", "v.", "达到；实现", "達到；實現", "Consistent practice helps learners achieve ambitious goals.", "持续练习能帮助学习者实现远大目标。", "a-（向）+ chief（头）→ 做到顶端", listOf("achieve a goal", "achieve success"), "A2", "学习"),
        VocabularyCard("v002", "approach", "/əˈprəʊtʃ/", "n./v.", "方法；接近", "方法；接近", "A balanced approach improves all four language skills.", "均衡的方法能提升四项语言技能。", "ap-（向）+ proach（近）", listOf("adopt an approach", "systematic approach"), "B1", "学习"),
        VocabularyCard("v003", "beneficial", "/ˌbenɪˈfɪʃəl/", "adj.", "有益的", "有益的", "Daily extensive reading is beneficial to vocabulary growth.", "每日泛读有益于词汇增长。", "bene（好）+ fic（做）+ ial", listOf("highly beneficial", "beneficial effect"), "B1", "教育"),
        VocabularyCard("v004", "coherent", "/kəʊˈhɪərənt/", "adj.", "连贯的；条理清楚的", "連貫的；條理清楚的", "A coherent essay guides the reader from claim to evidence.", "连贯的文章会引导读者从观点走向证据。", "co-（共同）+ her（黏着）+ ent", listOf("coherent argument", "coherent structure"), "B2", "写作"),
        VocabularyCard("v005", "compelling", "/kəmˈpelɪŋ/", "adj.", "令人信服的；引人入胜的", "令人信服的；引人入勝的", "The writer presents a compelling case for public transport.", "作者为公共交通提出了有力论证。", "com-（共同）+ pell（推动）", listOf("compelling evidence", "compelling reason"), "C1", "写作"),
        VocabularyCard("v006", "consequently", "/ˈkɒnsɪkwəntli/", "adv.", "因此；结果", "因此；結果", "Housing costs rose; consequently, many families moved outward.", "住房成本上升，因此许多家庭迁往外围。", "con- + sequ（跟随）+ ent + ly", listOf("and consequently", "consequently lead to"), "B2", "连接词"),
        VocabularyCard("v007", "constitute", "/ˈkɒnstɪtjuːt/", "v.", "构成；组成", "構成；組成", "Private vehicles constitute most rush-hour traffic.", "私家车构成了高峰期交通的大部分。", "con-（共同）+ stitut（建立）", listOf("constitute a threat", "constitute the majority"), "C1", "学术"),
        VocabularyCard("v008", "crucial", "/ˈkruːʃəl/", "adj.", "至关重要的", "至關重要的", "Clear feedback is crucial for deliberate practice.", "清晰反馈对刻意练习至关重要。", "cruc（十字路口）+ ial", listOf("play a crucial role", "crucial factor"), "B1", "学术"),
        VocabularyCard("v009", "deteriorate", "/dɪˈtɪəriəreɪt/", "v.", "恶化；退化", "惡化；退化", "Air quality may deteriorate when traffic volume increases.", "交通量增加时空气质量可能恶化。", "de-（向下）+ terior（更坏）", listOf("deteriorate rapidly", "health deteriorates"), "C1", "环境"),
        VocabularyCard("v010", "disparity", "/dɪˈspærəti/", "n.", "差异；不平等", "差異；不平等", "The policy reduced the disparity between urban and rural schools.", "该政策缩小了城乡学校之间的差距。", "dis-（分开）+ par（相等）+ ity", listOf("income disparity", "wide disparity"), "C1", "社会"),
        VocabularyCard("v011", "enhance", "/ɪnˈhɑːns/", "v.", "提高；增强", "提高；增強", "Green spaces enhance residents' quality of life.", "绿地提升居民生活质量。", "en-（使）+ hance（高）", listOf("enhance efficiency", "greatly enhance"), "B2", "城市"),
        VocabularyCard("v012", "feasible", "/ˈfiːzəbəl/", "adj.", "可行的", "可行的", "The proposal is affordable and technically feasible.", "该方案经济上可负担，技术上也可行。", "feas（做）+ ible（能够）", listOf("economically feasible", "feasible solution"), "C1", "写作"),
        VocabularyCard("v013", "fluctuate", "/ˈflʌktʃueɪt/", "v.", "波动", "波動", "Energy prices fluctuated throughout the decade.", "能源价格在这十年间持续波动。", "fluct（波浪）+ uate", listOf("fluctuate considerably", "prices fluctuate"), "B2", "小作文"),
        VocabularyCard("v014", "fundamental", "/ˌfʌndəˈmentəl/", "adj.", "根本的；基础的", "根本的；基礎的", "Trust is fundamental to effective teamwork.", "信任是高效团队合作的基础。", "fund（底）+ ment + al", listOf("fundamental change", "fundamental principle"), "B2", "学术"),
        VocabularyCard("v015", "implement", "/ˈɪmplɪment/", "v.", "实施；执行", "實施；執行", "Cities should implement safer cycling policies.", "城市应实施更安全的骑行政策。", "im-（进入）+ ple（填满）+ ment", listOf("implement a policy", "fully implement"), "B2", "政策"),
        VocabularyCard("v016", "inevitable", "/ɪnˈevɪtəbəl/", "adj.", "不可避免的", "不可避免的", "Some disruption is inevitable during major reforms.", "重大改革期间，一些扰动不可避免。", "in-（不）+ evit（避免）+ able", listOf("seem inevitable", "inevitable consequence"), "C1", "学术"),
        VocabularyCard("v017", "mitigate", "/ˈmɪtɪɡeɪt/", "v.", "缓解；减轻", "緩解；減輕", "Better insulation can mitigate household energy costs.", "更好的保温措施可以缓解家庭能源成本。", "mitig（温和）+ ate", listOf("mitigate the impact", "mitigate risk"), "C1", "环境"),
        VocabularyCard("v018", "notwithstanding", "/ˌnɒtwɪθˈstændɪŋ/", "prep./adv.", "尽管；虽然", "儘管；雖然", "Notwithstanding these limitations, the study remains valuable.", "尽管存在这些局限，该研究仍有价值。", "not + with + standing", listOf("notwithstanding the fact", "limitations notwithstanding"), "C2", "连接词"),
        VocabularyCard("v019", "predominantly", "/prɪˈdɒmɪnəntli/", "adv.", "主要地；占主导地", "主要地；佔主導地", "The region is predominantly rural.", "该地区主要是乡村地区。", "pre- + domin（统治）+ ant + ly", listOf("predominantly used", "predominantly urban"), "C1", "小作文"),
        VocabularyCard("v020", "prevalent", "/ˈprevələnt/", "adj.", "普遍的；流行的", "普遍的；流行的", "Remote work has become increasingly prevalent.", "远程办公变得愈发普遍。", "pre-（在前）+ val（强）+ ent", listOf("highly prevalent", "prevalent among"), "C1", "社会"),
        VocabularyCard("v021", "profound", "/prəˈfaʊnd/", "adj.", "深远的；深刻的", "深遠的；深刻的", "Digital media has had a profound effect on attention.", "数字媒体对注意力产生了深远影响。", "pro-（向前）+ fund（底）", listOf("profound impact", "profound change"), "C1", "科技"),
        VocabularyCard("v022", "proportion", "/prəˈpɔːʃən/", "n.", "比例；部分", "比例；部分", "The proportion of commuters using rail doubled.", "使用铁路通勤者的比例翻了一番。", "pro- + port（部分）+ ion", listOf("a large proportion", "in proportion to"), "B1", "小作文"),
        VocabularyCard("v023", "resilient", "/rɪˈzɪliənt/", "adj.", "有韧性的；能复原的", "有韌性的；能復原的", "Resilient learners treat mistakes as useful evidence.", "有韧性的学习者会把错误视为有用证据。", "re-（回）+ sili（跳）+ ent", listOf("highly resilient", "resilient community"), "C1", "学习"),
        VocabularyCard("v024", "substantial", "/səbˈstænʃəl/", "adj.", "大量的；实质性的", "大量的；實質性的", "The reform produced a substantial improvement in access.", "改革使可及性得到显著改善。", "sub-（在下）+ stant（站）+ ial", listOf("substantial increase", "substantial evidence"), "B2", "小作文"),
        VocabularyCard("v025", "sustainable", "/səˈsteɪnəbəl/", "adj.", "可持续的", "可持續的", "Compact cities can support more sustainable transport.", "紧凑型城市可以支持更可持续的交通。", "sus-（从下）+ tain（保持）+ able", listOf("sustainable development", "environmentally sustainable"), "B2", "环境"),
        VocabularyCard("v026", "tentative", "/ˈtentətɪv/", "adj.", "暂定的；试探性的", "暫定的；試探性的", "The researchers reached a tentative conclusion.", "研究人员得出了暂时性结论。", "tent（尝试）+ ative", listOf("tentative agreement", "tentative conclusion"), "C1", "学术"),
        VocabularyCard("v027", "undermine", "/ˌʌndəˈmaɪn/", "v.", "削弱；损害", "削弱；損害", "Poorly designed tests can undermine learner confidence.", "设计不良的测试会削弱学习者信心。", "under（在下）+ mine（挖）", listOf("undermine confidence", "seriously undermine"), "C1", "教育"),
        VocabularyCard("v028", "viable", "/ˈvaɪəbəl/", "adj.", "可行的；能存活的", "可行的；能存活的", "Cycling is a viable option for short urban journeys.", "骑行是城市短途出行的可行选择。", "vi（生命）+ able", listOf("commercially viable", "viable alternative"), "C1", "城市"),
        VocabularyCard("v029", "whereas", "/weərˈæz/", "conj.", "然而；相比之下", "然而；相比之下", "Bus use declined, whereas rail travel increased steadily.", "公交使用量下降，而铁路出行稳步上升。", "where + as", listOf("whereas in contrast", "whereas the latter"), "B2", "连接词"),
        VocabularyCard("v030", "widespread", "/ˈwaɪdspred/", "adj.", "广泛的；普遍的", "廣泛的；普遍的", "The initiative received widespread public support.", "该倡议获得了广泛公众支持。", "wide + spread", listOf("widespread concern", "widespread adoption"), "B2", "社会")
    )

    val levels = listOf(
        StudyLevel("foundation", "生存英语", "声音、核心句型与 1000 高频词", "A0 → A1", 8, 0xFF5B8C85, listOf("自然拼读", "基础听辨", "日常表达", "核心语法")),
        StudyLevel("elementary", "沟通起步", "建立 2000 词汇与连续表达", "A1 → A2", 10, 0xFF4E7F9B, listOf("场景词汇", "慢速听力", "段落阅读", "生活写作")),
        StudyLevel("intermediate", "能力成形", "从翻译思维转向英语组块", "A2 → B1", 12, 0xFF5B6F9E, listOf("词块记忆", "精听复述", "观点表达", "篇章结构")),
        StudyLevel("upper", "学术跃迁", "掌握长难句、论证和学术词汇", "B1 → B2", 14, 0xFF76639E, listOf("AWL 词汇", "讲座听力", "批判阅读", "议论文")),
        StudyLevel("ielts65", "雅思攻坚", "题型策略与限时稳定性", "IELTS 6.5", 12, 0xFF99624A, listOf("同义替换", "口语三部分", "Task 1/2", "全真套题")),
        StudyLevel("ielts75", "高分精修", "减少系统性失分并提高语言弹性", "IELTS 7.5", 10, 0xFF9B7A3E, listOf("干扰项识别", "自然互动", "精确论证", "复杂句控制")),
        StudyLevel("ielts8", "8 分冲刺", "在压力下保持准确、灵活与深度", "IELTS 8.0", 8, 0xFF173F3A, listOf("陌生题迁移", "语用细节", "高阶改写", "模考复盘"))
    )

    val listening = listOf(
        ListeningLesson("l01", "图书馆办证", "A2", "Good morning. I'd like to apply for a library card. I live nearby, but I have only recently moved into the area. Could you tell me which documents I need to bring?", "早上好。我想申请一张借书证。我住在附近，不过最近才搬到这个地区。请问需要带哪些证件？", "Why does the speaker need help?", "They want to apply for a library card.", "弱读与礼貌请求"),
        ListeningLesson("l02", "城市交通调查", "B1", "The council found that commuters valued reliability more than speed. Although express buses completed the route faster, passengers preferred the regular service because it arrived at predictable intervals.", "市政调查发现，通勤者看重可靠性多于速度。尽管快线公交完成路线更快，乘客仍偏好普通服务，因为班次间隔更可预测。", "Why did passengers prefer the regular service?", "It arrived at predictable intervals.", "转折后的关键信息"),
        ListeningLesson("l03", "大学讲座：记忆", "B2", "Retrieval practice is often mistaken for assessment. In fact, attempting to recall information changes the memory itself. The effort involved strengthens access routes, even when the first attempt is unsuccessful.", "提取练习常被误认为只是测验。事实上，尝试回忆信息会改变记忆本身。即使第一次失败，付出的努力也会强化提取路径。", "What happens when a learner tries to recall information?", "The access routes to the memory become stronger.", "学术定义与因果链"),
        ListeningLesson("l04", "自然保护访谈", "C1", "Conservation policies occasionally fail because they treat local communities as obstacles rather than partners. Durable programmes tend to align ecological goals with livelihoods, giving residents a practical reason to protect habitats.", "保护政策有时失败，是因为把当地社区当作障碍而非伙伴。持久项目通常会把生态目标与生计结合，让居民有实际理由保护栖息地。", "What distinguishes durable conservation programmes?", "They align ecological goals with local livelihoods.", "抽象概括与同义替换")
    )

    val reading = listOf(
        ReadingLesson("r01", "为什么走路有助于思考", "B1", "Walking has long been associated with creative thought. Recent experiments suggest that the benefit does not depend entirely on pleasant scenery. Participants who walked on a treadmill facing a blank wall still produced more varied ideas than those who remained seated. Movement may therefore loosen habitual patterns of attention, although it does not automatically improve the quality of every idea.", "Which statement best matches the experiments?", listOf("Only outdoor walking improved creativity.", "Walking increased the variety of ideas.", "Every idea became more accurate.", "Scenery was the main cause."), 1, "原文强调 produced more varied ideas；它没有说每个想法质量都更高。"),
        ReadingLesson("r02", "城市树木的隐性价值", "B2", "Urban trees are often assessed through visible benefits such as shade and appearance. Their less obvious contribution is hydrological. Leaves interrupt rainfall, roots create channels in compacted soil, and the surrounding organic matter stores water. Yet planting campaigns that count stems rather than survival can exaggerate impact. A young tree that dies after two summers provides little long-term flood protection.", "What criticism does the writer make of some planting campaigns?", listOf("They choose unattractive species.", "They underestimate rainfall.", "They focus on numbers planted instead of survival.", "They damage compacted soil."), 2, "count stems rather than survival 是题干 focus on numbers planted instead of survival 的同义替换。"),
        ReadingLesson("r03", "专业知识与直觉", "C1", "Expert intuition is neither mysterious nor universally reliable. It develops when a person encounters stable patterns and receives timely feedback. A chess master can recognise a meaningful board configuration because similar relationships have appeared thousands of times. By contrast, predictions in rapidly changing systems may feel equally confident while resting on weak regularities. Confidence alone is therefore poor evidence of expertise.", "According to the passage, when is expert intuition most dependable?", listOf("When the expert feels certain", "When patterns are stable and feedback is timely", "When a system changes rapidly", "When decisions are made without practice"), 1, "首段第二句直接给出两个条件：stable patterns 与 timely feedback。")
    )

    val grammar = listOf(
        GrammarQuestion("g01", "A2", "If she ___ earlier, she can join the speaking club.", listOf("finish", "finishes", "finished", "will finish"), 1, "真实条件句中，if 从句用一般现在时，主句可用情态动词。"),
        GrammarQuestion("g02", "B1", "The new library, ___ opened last month, is already crowded.", listOf("who", "where", "which", "what"), 2, "非限定性定语从句修饰事物，用 which。"),
        GrammarQuestion("g03", "B2", "Hardly ___ the lecture begun when the fire alarm sounded.", listOf("had", "has", "did", "was"), 0, "Hardly 位于句首触发部分倒装；先发生的动作使用过去完成时。"),
        GrammarQuestion("g04", "C1", "The policy would have been more effective ___ local residents been consulted.", listOf("if", "had", "were", "unless"), 1, "省略 if 的虚拟条件句把 had 提至主语前：had residents been consulted。")
    )

    val essayTasks = listOf(
        EssayTask("w01", "IELTS Writing Task 2", "城市与交通", "Some people believe that cities should invest more in public transport than in building new roads. To what extent do you agree or disagree?", 250, listOf("明确立场，不要只列双方观点", "每个主体段只推进一个中心理由", "用具体机制解释投资如何影响拥堵")),
        EssayTask("w02", "IELTS Writing Task 2", "教育与科技", "Some schools are replacing printed books with digital materials. Do the advantages of this development outweigh the disadvantages?", 250, listOf("先定义衡量 advantages 是否 outweigh 的标准", "区分设备本身与教学设计的影响", "结论要回扣比较标准")),
        EssayTask("w03", "IELTS Writing Task 2", "工作与生活", "Many employees now work from home. Is this a positive or negative development?", 250, listOf("可以采用总体正面但有条件的立场", "分别讨论个人、企业与城市层面", "避免把例子当作论证本身")),
        EssayTask("w04", "IELTS Writing Task 1", "趋势概述练习", "The chart shows the proportion of commuters using car, bus and rail between 2000 and 2025. Summarise the main features and make relevant comparisons.", 150, listOf("开头改写题目，不解释原因", "Overview 写两到三个最大特征", "按趋势或类别分组，不逐年报数"))
    )
}
