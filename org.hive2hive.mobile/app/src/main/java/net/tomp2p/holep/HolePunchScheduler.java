package net.tomp2p.holep;

/**
 * This class is used as a {@link Thread} specifically for the
 * {@link HolePuncher}. It calls the tryConnect() method on the
 * {@link HolePuncher} every second until it reached the given numberOfTrials.
 * 
 * @author Jonas Wagner
 * 
 */
public class HolePunchScheduler implements Runnable {

	private static final int FIVE_MINUTES = 300;
	private static final int ONE_SECOND_MILLIS = 1000;
	private int numberOfTrials;
	private HolePuncher holePuncher;

	public HolePunchScheduler(int numberOfTrials, HolePuncher holePuncher) {
		// 300 -> 5min
		if (numberOfTrials > FIVE_MINUTES) {
			throw new IllegalArgumentException("numberOfTrials can't be higher than 300 (5min)!");
		} else if (numberOfTrials < 1) {
			throw new IllegalArgumentException("numberOfTrials must be at least 1!");
		} else if (holePuncher == null) {
			throw new IllegalArgumentException("HolePuncher can't be null!");
		} else {
			this.numberOfTrials = numberOfTrials;
			this.holePuncher = holePuncher;
		}
	}

	@Override
	public void run() {
		while (numberOfTrials != 0) {
			try {
				holePuncher.tryConnect();
			} catch (Exception e1) {
				e1.printStackTrace();
			}
			numberOfTrials--;
			try {
				Thread.sleep(ONE_SECOND_MILLIS);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
}
