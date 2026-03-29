package experiment.search.realization;

import java.util.Scanner;
import java.util.concurrent.*;

public class Main {
    //我电脑16个逻辑处理器, 这是CPU密集型的文本处理, 主要瓶颈在文本处理计算上
    public static ExecutorService ex = new ThreadPoolExecutor(
            16, 16,
            60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100),
            Executors.defaultThreadFactory(), new ThreadPoolExecutor.CallerRunsPolicy()
    );

    public static void main(String[] args) {
        KeySearch keySearch = new KeySearch(ex);

        Scanner sc = new Scanner(System.in);
        System.out.println("=====开始使用文件搜索=====");
        System.out.println("当前是对文件" + keySearch.getDirectory() + "下的文件进行搜索");
        while (true) {
            System.out.println("请输入要搜索的关键字:");

            String key = sc.nextLine();
            if ("离开".equals(key)) break;
            keySearch.search(key);
        }

        ex.shutdown();//服务器一般是不用关闭的，这里因为在电脑上运行才关闭
    }
}
