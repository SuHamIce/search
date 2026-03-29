package experiment.search.realization;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 查找为遍历式查找文档中，有无符合单词，模拟的是待查找文库比较大的情况，小文库可以直接记录所有词语出现的位置。
 *-
 * 模糊查找依赖于词库，词语会找尽量小的修改距离的词库词，然后遍历查找
 * 词库必须尽量大，至少可以覆盖文件，才能实现优秀的模糊查找
 * 如果词库过大，可能影响性能，实际运用建议高凭词放入内存
 *-
 * 测试了很多极端用例，出现了多词模糊搜索后显示不高亮的问题，还有输入大量乱码报错的问题
 * 我修改问题发现变量都缠在一起，改不来，所以这一部分用ai改的，所以有的函数的部分细节被ai改过
 */
public class KeySearch {
    private String directory = "Java-experiment-1\\src\\experiment\\search"; //这里是相对路径，是项目文件夹路径，在这个下面放其他所有东西，这个文件夹下会有vocabularies,realization,lab4三个文件夹，不能乱
    ExecutorService ex; //模拟从外部导入的公用线程池，到时候我在Main里开一个
    private static final String RED = "\u001B[31m";
    private static final String RESET = "\u001B[0m";

    //对于一个服务器来说，搜索程序应该是一直运行的，应该把高频词放入内存中，可以使搜索更高效
    //private static HashSet<String> vocabularies = new HashSet<>();

    private KeySearch(){} //应该从外部传入公用线程池，一个类不应该自己开线程池，所以不能用这个构造器

    public KeySearch(ExecutorService ex){
        this.ex = ex;
        ensureSafety();
    }

    public KeySearch(String directory, ExecutorService ex) {
        this.directory = directory;
        this.ex = ex;
        ensureSafety();
    }

    //后面加的，怕文件目录不对
    private void ensureSafety(){
        Scanner sc = new Scanner(System.in);
        while (true){
            File fir = new File(directory);
            if (!fir.exists()){
                System.out.println("=====请检查目录是否正确=====");
                System.out.println("请输入新目录: ");
                directory = sc.next();
            }
            else break;
        }
        if (!isReliable()) {
            System.out.println("=====词典不可用，请等待词典生成=====");
            initVocabulary();
        }
        //不知道为什么，这里有这个close会抛异常
//        sc.close();
    }

    public void setDirectory(String directory) {
        this.directory = directory;
    }

    public String getDirectory() {
        return directory;
    }

    //关键函数，查找单词
    public boolean search(String key){
        if (key == null || key.isEmpty()) {
            System.out.println("=====请别搜索空单词=====");
            return false;
        }
        key = key.toLowerCase();
        System.out.println("=====搜索结果=====");
        System.out.println();

        //这里是精确搜索
        HashMap<String, ArrayList<String>> matchedInfo = getMatchedInfo(key, false);
        if (!matchedInfo.isEmpty()){
            for (String fileName : matchedInfo.keySet()){
                System.out.println("匹配到的文件 " + fileName +
                        " 共" + matchedInfo.get(fileName).size() + "个结果" + ": ");
                print(matchedInfo.get(fileName), key, false);
                System.out.println();
            }
            return true;
        }

        System.out.println("=====进行模糊搜索=====");

        //这里是第一次模糊搜索，因为你可能是没打完单词
        matchedInfo = getMatchedInfo(key, true);
        if (!matchedInfo.isEmpty()){
            for (String fileName : matchedInfo.keySet()){
                System.out.println("匹配到的文件 " + fileName +
                        " 共" + matchedInfo.get(fileName).size() + "个结果" + ": ");
                print(matchedInfo.get(fileName), key, true);
                System.out.println();
            }
            return true;
        }

        if (!isReliable()){
            System.out.println("=====词典不可用，请等待词典生成=====");
            initVocabulary();
        }

        //这里是第二次模糊搜索, 主要怕你输入的词有错
        HashMap<Integer, HashSet<String>>[] candidates = getApproximateMatchedWords(key);
        // 新增：判断是否有有效候选词，解决了大一堆长乱码报错的问题
        boolean hasValidCandidate = false;
        for (HashMap<Integer, HashSet<String>> candidate : candidates) {
            if (candidate != null && !candidate.isEmpty()) {
                hasValidCandidate = true;
                break;
            }
        }
        if (candidates.length == 0 || !hasValidCandidate) {
            System.out.println("=====没有匹配的词=====");
            return false;
        }

        //生成所有模糊词组合（每个拆分部分选一个候选词）
        List<List<String>> allCombinations = generateAllCombinations(candidates);
        if (allCombinations.isEmpty()) {
            System.out.println("=====没有匹配的词=====");
            return false;
        }

        //计算每个组合的距离之和，按距离之和升序排序
        List<CombinationWithTotalDistance> sortedCombinations = calculateAndSortTotalDistance(allCombinations, candidates);

        //按距离之和从小到大找，找到匹配就返回
        for (CombinationWithTotalDistance combo : sortedCombinations) {
            List<String> wordCombo = combo.combination;
            System.out.println("尝试模糊词: " + String.join(" ", wordCombo));

            // 验证组合是否有匹配（词间允许任意非字符）
            HashMap<String, ArrayList<String>> comboMatchedInfo = matchMultiWordCombo(wordCombo);
            if (!comboMatchedInfo.isEmpty()) {
                // 打印匹配结果
                for (String fileName : comboMatchedInfo.keySet()) {
                    System.out.println("匹配到的文件 " + fileName +
                            " 共" + comboMatchedInfo.get(fileName).size() + "个结果" + ": ");
                    print(comboMatchedInfo.get(fileName), String.join(" ", wordCombo), true);
                    System.out.println();
                }
                return true; // 找到结果，不继续找更远的组合
            }
        }

        System.out.println("=====没有匹配的词=====");
        return false;
    }

    //查flag文件
    private boolean isReliable(){
        File flag = new File(directory  + "\\" + "vocabularies" + "\\flag.txt");
        if (!flag.exists()) return false;
        try (FileReader fr = new FileReader(flag); BufferedReader br = new BufferedReader(fr)){
            String line = br.readLine();
            if (line == null) return false;
            return line.contains("true");
        }catch (IOException e) {
            e.printStackTrace();
            System.out.println("检查词典是否可用时出错");
        }
        return false;
    }

    //在lab中精确检索一个词
    public HashMap<String, ArrayList<String>> getMatchedInfo(String key, boolean informal){
        HashMap<String, ArrayList<String>> matchedInfo = new HashMap<>();
        List<Future<?>> futures = new ArrayList<>();
        // 新增：校验lab4文件夹
        File lab4Dir = new File(directory + "\\lab4");
        if (!lab4Dir.exists() || !lab4Dir.isDirectory()) {
            System.out.println("=====错误：lab4文件夹不存在或不是目录=====");
            return matchedInfo;
        }
        File[] files = lab4Dir.listFiles();
        if (files == null || files.length == 0) {
            System.out.println("=====lab4文件夹中没有文件=====");
            return matchedInfo;
        }
        // 遍历文件
        for (File file : files){
            if (file.getName().endsWith(".txt")){
                Future future = ex.submit(() -> getMatchedInfoWithOneFile(informal, file, key, matchedInfo));
                futures.add(future);
            }
        }

        //等待线程全部完成
        for (Future<?> future : futures){
            try {
                future.get();
            }catch (Exception e){
                e.printStackTrace();
                System.out.println("等待单词检索全部完成时出异常");
                System.out.println("精准搜索的单文件操作有异常");
            }
        }

        return matchedInfo;
    }

    //直接找一个文件里有没有匹配的词，加informal表式不严肃匹配，懒得再写一个函数了
    public void getMatchedInfoWithOneFile(boolean informal,
            File file, String key, HashMap<String, ArrayList<String>> allMatchedInfo){
        ArrayList<String> matchedInfo = new ArrayList<>();
        try (FileReader fr = new FileReader(file); BufferedReader br = new BufferedReader(fr)){
            String line;
            if (informal) {
                while ((line = br.readLine()) != null){
                    if (line.toLowerCase().contains(key)){
                        matchedInfo.add(line);
                    }
                }
            }
            else {
                Pattern p = Pattern.compile("\\b" + key + "\\b", Pattern.CASE_INSENSITIVE);
                while ((line = br.readLine()) != null){
                    Matcher m = p.matcher(line);
                    if (m.find()){
                        matchedInfo.add(line);
                    }
                }
            }
        }catch (IOException e){
            e.printStackTrace();
            System.out.println("精准搜索的单文件操作有异常");
        }

        if (!matchedInfo.isEmpty()){
            synchronized (allMatchedInfo) {
                allMatchedInfo.put(file.getName(), matchedInfo);
            }
        }
    }

    //打印句子，不过加了个改颜色功能，效率有点低。
    public void print(ArrayList<String> info, String key, boolean informal) {
        if (info == null || info.isEmpty() || key == null || key.isEmpty()) {
            return;
        }

        // 拆分关键词为单词数组（按非字母拆分，兼容多词）
        String[] words = key.split("[^a-zA-Z]+");
        List<String> validWords = new ArrayList<>();
        for (String word : words) {
            if (!word.isEmpty()) {
                validWords.add(word.toLowerCase()); // 统一转为小写
            }
        }
        if (validWords.isEmpty()) {
            return;
        }

        // 遍历每行，逐个单词高亮
        int i = 1;
        for (String line : info) {
            System.out.print("<" + i++ + ">: ");
            String highlightedLine = line;
            for (String word : validWords) {
                String regex;
                if (informal) {
                    // 模糊搜索：子串匹配（忽略大小写）
                    regex = "(?i)" + Pattern.quote(word);
                } else {
                    // 精确搜索：独立词匹配（忽略大小写，带单词边界）
                    regex = "(?i)\\b" + Pattern.quote(word) + "\\b";
                }
                highlightedLine = highlightedLine.replaceAll(regex, RED + "$0" + RESET);
            }
            System.out.println(highlightedLine);
        }
    }

    //初始化词典，只用进行一次
    public void initVocabulary(){
        File vocabularies = new File(directory + "\\vocabularies");
        File labsDir = new File(directory + "\\lab4");
        HashMap<Integer, HashSet<String>> voc = new HashMap<>();

        // 新增：校验lab4文件夹
        if (!labsDir.exists() || !labsDir.isDirectory()) {
            System.out.println("=====错误：初始化词典失败，lab4文件夹不存在或不是目录=====");
            return;
        }
        File[] labFiles = labsDir.listFiles();
        if (labFiles == null || labFiles.length == 0) {
            System.out.println("=====错误：初始化词典失败，lab4文件夹中没有文件=====");
            return;
        }

        List<Future<?>> futures = new ArrayList<>();
        // 遍历labFiles
        for (File file : labFiles){
            if (file.getName().endsWith(".txt")){
                Future future = ex.submit(() -> initVocWithOneFile(voc, file));
                futures.add(future);
            }
        }

        //等带线程全部完成
        for (Future<?> future : futures){
            try {
                future.get();
            }catch (Exception e){
                e.printStackTrace();
                System.out.println("初始化词典有异常");
                System.out.println("等待单词检索全部完成时出异常");
            }
        }

        futures = new ArrayList<>();
        vocabularies.mkdir();
        for (Integer length : voc.keySet()){
            Future future = ex.submit(() -> writeVocWithOneLen(voc.get(length),
                    new File(vocabularies.getPath() + "\\" + length + ".txt")));
            futures.add(future);
        }

        //等待线程全部完成
        for (Future<?> future : futures){
            try {
                future.get();
            }catch (Exception e){
                e.printStackTrace();
                System.out.println("初始化词典有异常");
                System.out.println("在等待词典写入的时候出异常");
            }
        }

        System.out.println("=====初始化词典完成=====");

        //记录词典的修改是有必要的，如果有更大更好的词典可以直接使用。但是发现词典过旧，就不应该继续使用。
        //写一个flush函数来操作这个可能更好，但是这里目前就这一个地方要用，所以暂时这么写。
        File flag = new File(directory  + "\\" + "vocabularies" + "\\flag.txt");
        try (FileWriter fw = new FileWriter(flag)){
            fw.write("词典是否可信赖: ");
            fw.write("true"); //我模拟的这个量应该经常刷新。
            fw.write("\n");
            fw.write("更新时间: ");
            fw.write(System.currentTimeMillis() + "");
            fw.write("\n");
            int count = 0;
            for (Integer length : voc.keySet()){
                count += voc.get(length).size();
            }
            fw.write("词语言数量: " + count);
            fw.write("\n");
            fw.write("词典来源: ");
            fw.write("库文件检索");
            fw.write("\n");
        }catch (IOException e){
            e.printStackTrace();
            System.out.println("更新词典初始化时间有问题");
        }
    }

    //在一个lab中，搜索单词。
    private void initVocWithOneFile(HashMap<Integer, HashSet<String>> voc, File file){
        try (FileReader fr = new FileReader(file); BufferedReader br = new BufferedReader(fr)){
            String line;
            while ((line = br.readLine()) != null){
                String[] words = line.split("[^a-zA-Z]+");
                for (String word : words){
                    if (word.length() > 0){
                        word = word.toLowerCase();
                        synchronized (voc) {
                            if (!voc.containsKey(word.length())){
                                voc.put(word.length(), new HashSet<>());
                            }
                            voc.get(word.length()).add(word);
                        }
                    }
                }
            }
        }catch (IOException e){
            e.printStackTrace();
            System.out.println("初始化词典有异常");
            System.out.println("异常文件为:" + file.getName());
        }
    }

    //把内存找到的词写入词典文件
    private void writeVocWithOneLen(HashSet<String> words, File file){
        try (FileWriter fw = new FileWriter(file); BufferedWriter bw = new BufferedWriter(fw)){
            for (String word : words) {
                bw.write(word);
                bw.newLine();
            }
        }catch (IOException e){
            e.printStackTrace();
            System.out.println("初始化词典有异常");
            System.out.println("异常文件为:" + file.getName());
        }
    }

    //计算两个字符串的修改距离，力扣hot100经典算法
    private int editDistance(String s1, String s2){
        int m = s1.length();
        int n = s2.length();
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 0; i <= m; i++){
            dp[i][0] = i;
        }
        for (int j = 0; j <= n; j++){
            dp[0][j] = j;
        }
        for (int i = 1; i <= m; i++){
            for (int j = 1; j <= n; j++){
                if (s1.charAt(i - 1) == s2.charAt(j - 1)){
                    dp[i][j] = dp[i - 1][j - 1];
                }else {
                    dp[i][j] = Math.min(dp[i - 1][j - 1] + 1, Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1));
                }
            }
        }
        return dp[m][n];
    }

    private HashMap<Integer, HashSet<String>>[] getApproximateMatchedWords(String wholeWord) {
        String[] parts = getParts(wholeWord);
        // 修正：数组长度应为实际输入拆分的有效部分数量（非空字符串的数量）
        int validPartCount = 0;
        for (String part : parts) {
            if (part.length() > 0) {
                validPartCount++;
            }
        }

        // 用有效部分数量初始化数组，避免长度不足
        HashMap<Integer, HashSet<String>>[] approximateMatchedWords = new HashMap[validPartCount];
        int head = 0;
        for (String part : parts) {
            if (part.length() == 0) {
                continue;
            }
            // 确保不越界（head < 数组长度）
            if (head < approximateMatchedWords.length) {
                approximateMatchedWords[head++] = getApproximateMatchedWordsForPart(part);
            }
        }

        return approximateMatchedWords;
    }

    //得到一个词的模糊词，可能有很多个，也可能0个。如果0个我就认为找不到这个词的模糊词。
    private HashMap<Integer, HashSet<String>> getApproximateMatchedWordsForPart(String word){
        //对于越长的词，我按比例放宽字长查找范围。
        int gap;
        gap = word.length() / 5 + 1;

        //对于不同长度的词，我对于模糊词的修改长度也有比例要求，比如5个字母以内的词，我认为打错一个单词是正常的。而2个字母以内的，本身没多少字母，只允许精准搜索
        HashMap<Integer, HashSet<String>> candidatesForOnePart = new HashMap<>();
        int maxDistance;
        maxDistance = word.length() / 3;

        List<Future<?>> futures = new ArrayList<>();
        for (int i = word.length() - gap; i <= word.length() + gap; i++){
            int len =  i;
            Future future = ex.submit(() -> getCandidates(candidatesForOnePart, word, len, maxDistance));
            futures.add(future);
        }

        //等待线程全部完成
        for (Future<?> future : futures){
            try {
                future.get();
            }catch (Exception e){
                e.printStackTrace();
                System.out.println("模糊搜索找模糊词有异常");
                System.out.println("在等待词典读取的时候出异常");
            }
        }

        return candidatesForOnePart;
    }

    //从一个长度的词典中得到一个词的模糊词
    private void getCandidates(HashMap<Integer, HashSet<String>> candidates, String part, int length, int maxDistance){
        File voc = new File(directory + "\\vocabularies\\" + length + ".txt");
        // 新增：判断词典文件是否存在，不存在直接返回，不抛异常
        if (!voc.exists()) {
            return;
        }
        try (FileReader fr = new FileReader(voc); BufferedReader br = new BufferedReader(fr)){
            String wordInVoc;
            while ((wordInVoc = br.readLine()) != null){
                int distance = editDistance(wordInVoc, part);
                if (distance <= maxDistance){
                    synchronized (candidates) {
                        if (!candidates.containsKey(distance)){
                            candidates.put(distance, new HashSet<>());
                        }
                        candidates.get(distance).add(wordInVoc);
                    }
                }
            }
        }catch (IOException e){
            e.printStackTrace();
            System.out.println("模糊搜索找模糊词有异常");
            System.out.println("异常文件为:" + voc.getName());
        }
    }

    private String[] getParts(String key){
        String[] parts = key.split("[^a-zA-Z]+");
        return parts;
    }

    private List<List<String>> generateAllCombinations(HashMap<Integer, HashSet<String>>[] candidates) {
        List<List<String>> combinations = new ArrayList<>();
        // 新增：先判断是否有有效候选词，避免空指针
        if (candidates == null || candidates.length == 0) return combinations;
        HashMap<Integer, HashSet<String>> firstValidCandidate = null;
        for (HashMap<Integer, HashSet<String>> candidate : candidates) {
            if (candidate != null && !candidate.isEmpty()) {
                firstValidCandidate = candidate;
                break;
            }
        }
        if (firstValidCandidate == null) return combinations;

        // 初始化第一个有效候选词的单词
        List<String> firstPartWords = new ArrayList<>();
        for (HashSet<String> words : firstValidCandidate.values()) {
            for (String word : words) {
                firstPartWords.add(word.toLowerCase());
            }
        }
        for (String word : firstPartWords) {
            List<String> initCombo = new ArrayList<>();
            initCombo.add(word);
            combinations.add(initCombo);
        }

        // 拼接后续部分的候选词
        for (int i = 1; i < candidates.length; i++) {
            if (candidates[i] == null || candidates[i].isEmpty()) {
                combinations.clear();
                break;
            }
            List<String> currentPartWords = new ArrayList<>();
            for (HashSet<String> words : candidates[i].values()) {
                for (String word : words) {
                    currentPartWords.add(word.toLowerCase());
                }
            }

            List<List<String>> newCombinations = new ArrayList<>();
            for (List<String> existingCombo : combinations) {
                for (String currentWord : currentPartWords) {
                    List<String> newCombo = new ArrayList<>(existingCombo);
                    newCombo.add(currentWord);
                    newCombinations.add(newCombo);
                }
            }
            combinations = newCombinations;
        }
        return combinations;
    }

    private List<CombinationWithTotalDistance> calculateAndSortTotalDistance(
            List<List<String>> allCombinations, HashMap<Integer, HashSet<String>>[] candidates) {
        List<CombinationWithTotalDistance> result = new ArrayList<>();

        for (List<String> combo : allCombinations) {
            int totalDistance = 0;
            boolean isValid = true;
            for (int i = 0; i < combo.size(); i++) {
                String word = combo.get(i);
                int distance = -1;
                // 找当前词的修改距离
                for (Map.Entry<Integer, HashSet<String>> entry : candidates[i].entrySet()) {
                    if (entry.getValue().contains(word)) {
                        distance = entry.getKey();
                        break;
                    }
                }
                if (distance == -1) {
                    isValid = false;
                    break;
                }
                totalDistance += distance;
            }
            if (isValid) {
                result.add(new CombinationWithTotalDistance(combo, totalDistance));
            }
        }

        // 按距离之和排序
        Collections.sort(result, (a, b) -> a.totalDistance - b.totalDistance);
        return result;
    }

    private HashMap<String, ArrayList<String>> matchMultiWordCombo(List<String> wordCombo) {
        HashMap<String, ArrayList<String>> matchedInfo = new HashMap<>();
        List<Future<?>> futures = new ArrayList<>();

        // 构建多词正则：\b词1\b\W*\b词2\b...
        StringBuilder regexBuilder = new StringBuilder();
        for (int i = 0; i < wordCombo.size(); i++) {
            String word = wordCombo.get(i).toLowerCase();
            regexBuilder.append("\\b").append(Pattern.quote(word)).append("\\b");
            if (i != wordCombo.size() - 1) {
                regexBuilder.append("\\W*");
            }
        }
        Pattern comboPattern = Pattern.compile(regexBuilder.toString(), Pattern.CASE_INSENSITIVE);

        // 多线程搜索文件
        for (File file : new File(directory + "\\lab4").listFiles()) {
            if (file.getName().endsWith(".txt")) {
                Future<?> future = ex.submit(() -> {
                    ArrayList<String> lines = new ArrayList<>();
                    try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            if (comboPattern.matcher(line).find()) {
                                lines.add(line);
                            }
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                        System.out.println("组合匹配异常，文件：" + file.getName());
                    }
                    if (!lines.isEmpty()) {
                        synchronized (matchedInfo) {
                            matchedInfo.put(file.getName(), lines);
                        }
                    }
                });
                futures.add(future);
            }
        }

        // 等待线程完成
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println("组合匹配等待线程异常");
            }
        }

        return matchedInfo;
    }

    //内部类，为了实现多词的模糊匹配设计的
    private static class CombinationWithTotalDistance {
        List<String> combination; // 模糊词组合（如 [car, red]）
        int totalDistance;        // 距离之和

        public CombinationWithTotalDistance(List<String> combination, int totalDistance) {
            this.combination = combination;
            this.totalDistance = totalDistance;
        }
    }
}


