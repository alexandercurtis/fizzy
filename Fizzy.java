/* Fizzy bubbles applet        */
/* (c) 2002 Alex Curtis */

import java.applet.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
//import java.util.Vector;
import java.net.URL;
import java.io.InputStream;
//import java.util.StringTokenizer;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.MalformedURLException;


// Parameters
// whitebubbles=true // sets white bubbles, otherwise they are black
// texdata // name of the file of bubble textures
// bgimage // name of the file to use as the background image
// nucleusColour // colour in the background image where bubbles can form. specify as hex int.

public class Fizzy extends Applet implements Runnable{
  // Applet stuff
  protected int m_width; // applet window width
  protected int m_height; // applet window height

  protected final static String version=new String("Fizzy applet v1.0 (C)2002 Alex Curtis");

  // Particle data
  protected static final int NUM_BUBBLES = 400;
  protected static final int BUBBLE_DIAM = 20; // max allowed bubble diameter
  protected int[] m_bubblesX = new int[ NUM_BUBBLES ];
  protected int[] m_bubblesY = new int[ NUM_BUBBLES ];
  protected int[] m_bubblesR = new int[ NUM_BUBBLES ];
  protected int[] m_bubblesQ = new int[ NUM_BUBBLES ];
  protected boolean[] m_bubblesAlive = new boolean[ NUM_BUBBLES ];
  protected int m_freeIndex; // Used to find quickly a free bubble

  // Nucleation site data
  protected static final int NUM_NUCLEUS = 20;
  protected int[] m_nucleusX = new int[ NUM_NUCLEUS ];
  protected int[] m_nucleusY = new int[ NUM_NUCLEUS ];
  protected int[] m_nucleusT = new int[ NUM_NUCLEUS ];
  protected int[] m_nucleusR = new int[ NUM_NUCLEUS ];
  protected static final int BUBBLE_RATE_MAX = 16;
  protected static final int BUBBLE_RATE_MIN = 4;
  protected boolean m_randomNucleii;
  protected int m_nucleusColour;


  // Background image
  protected int[] m_imgPixels;
  protected Image bgImage;

  // Particle textures
  protected int[][] m_textures = new int[ BUBBLE_DIAM ][]; // One texture for each possible radius value, but textures can be null in which case the nearest larger one is used. NB probably won't use odd-sized textures cos they can't be centred.

  // Rendering stuff
  protected static final int SUBPIXEL_RES = 2; // particle coordinates are 1/3 pixel resolution. (NB values >16 will require modification of sub-pixel -> pixel merging code.)
  protected MemoryImageSource m_memSrc;
  protected int[] m_subpixels; // TBD save memory by using a smaller, rolling window, given that bubbles can't overlap others more than BUBBLE_DIAM away.
  protected int[] m_pixels;
  protected boolean m_whiteBubbles;

  Thread m_gameThread;        // The thread which runs the game, independently of the browser.
  boolean m_paused; // True indicates the game is paused and not to update anything.
  boolean m_shutDown; // True if the game thread should exit.

  // User interaction
  boolean m_mouse;
  int m_mouseX;
  int m_mouseY;

  Image m_backBufferImage;  // The actual back buffer.

  public void init() {

    m_mouse = false;

    // Parse param for whitebubbles flag
    m_whiteBubbles = false;
    try{
      if( getParameter("whitebubbles").equals("true") ) {
        m_whiteBubbles = true;
      }
    }catch(Exception e){
      // Nothing
    }

    // Parse param for nucleus colour
    m_randomNucleii = true;
    m_nucleusColour = -1;
    try{
      m_nucleusColour = Integer.parseInt(getParameter("nucleusColour"),16);
      m_randomNucleii  = false;
    }catch(Exception e){
      // Nothing
    }

    // Load the background image.
    MediaTracker t = new MediaTracker(this);
    /*Image*/ bgImage = getImage(getCodeBase(), getParameter("bgimage"));
    t.addImage( bgImage, 0 );
    try
    {
      t.waitForAll();
    } catch (InterruptedException e) {/* Do nothing. */}
    if(t.isErrorAny())
    {
      showStatus("Error Loading Image!");
      while(true); // Infinite loop
    }

    // Get applet size
    m_width = 200;//getWidth(); "method not found" at runtime
    m_height = 200;//getHeight();

/*
    if( bgImage.getWidth( this ) != m_width && bgImage.getHeight( this ) != m_height ) {
      showStatus("Image and applet must be the same size (" + bgImage.getWidth( this ) + " x " + bgImage.getHeight( this ) + ") vs. (" + m_width  + " x " + m_height + ")" );
      while(true); // Infinite loop
    }
*/

    m_width = bgImage.getWidth( this );
    m_height = bgImage.getHeight( this );


    // Check Integer is big enough to hold the coords. (It blimmin well should be!)
    if( Integer.MAX_VALUE / m_width < SUBPIXEL_RES  ||  Integer.MAX_VALUE / m_width < SUBPIXEL_RES ) {
      System.out.println("You're having a laugh with your resolution settings!");
      return;
    }

    m_subpixels = new int[ m_width * m_height * SUBPIXEL_RES * SUBPIXEL_RES ]; // TBD save memory by using a smaller, rolling window, given that bubbles can't overlap others more than BUBBLE_DIAM away.
    m_pixels = new int[ m_width * m_height ];

    // Randomly place particles.

    for( int i=0; i<NUM_BUBBLES; ++i ) {
      m_bubblesAlive[i] = false;
    }
    m_freeIndex = 0;


    // Load particle textures
    for( int i=0; i<BUBBLE_DIAM; ++i ) {
      m_textures[i] = null;
    }
    String datFile = getParameter("texdata");
    if( datFile != null ) {
      readTextures( datFile );
    }



    // Extract pixels from bg
    m_imgPixels = new int[ m_width * m_height ];
    PixelGrabber pg = new PixelGrabber( bgImage, 0, 0, m_width, m_height, m_imgPixels, 0, m_width );
    // Grab pixels:
    try {
      pg.grabPixels();
    } catch (InterruptedException e) {
      showStatus("Error grabbing pixels");
      while(true); // Infinite loop
    }

    // Place nucleation sites
    int sitesLeft = NUM_NUCLEUS;
    if( !m_randomNucleii ) {
      // Scan image to find nucleation sites
      int q = m_nucleusColour & 0x00ffffff;

      // First scan image to find out how much of a colour there is
      int pixelsAvailable = 0;
      for( int i = (m_width * m_height)-1; i >= 0; --i ) {
        if ((m_imgPixels[i]&0xffffff) == q) {
          ++pixelsAvailable;
        }
      }

System.out.println("Counted " + pixelsAvailable + " possible nucleation sites");
/*
if( pixelsAvailable != 8261 ) {
      showStatus("Sorry you can't customise this version, please wait for a later version!");
      while(true); // Infinite loop
}*/

      // Now scan image and place nucleation sites
      int i = 0;
      for( int y=0; sitesLeft>0 && y<m_height; ++y ) {
        for( int x=0; sitesLeft>0 && x<m_width; ++x ) {
          if( (m_imgPixels[i++]&0xffffff) == q ) {
            // Probability of chosing this as a site is sitesLeft/pixelsAvailable
            double r = (double)sitesLeft / (double)pixelsAvailable;
            if( Math.random() < r ) {
              m_nucleusX[NUM_NUCLEUS-sitesLeft] = SUBPIXEL_RES*x + (int)(Math.random() * (double)(SUBPIXEL_RES) );
              m_nucleusY[NUM_NUCLEUS-sitesLeft] = SUBPIXEL_RES*y + (int)(Math.random() * (double)(SUBPIXEL_RES) );
              m_nucleusT[NUM_NUCLEUS-sitesLeft] = (int)(Math.random() * (double)(BUBBLE_RATE_MAX-BUBBLE_RATE_MIN) )+BUBBLE_RATE_MIN;
              m_nucleusR[NUM_NUCLEUS-sitesLeft] = 0;
              --sitesLeft;
              if(sitesLeft == 0 ) { break; }
            }
            --pixelsAvailable;
          }
        }
      }
    }

    // Randomly place remaining nucleation sites
    for( int i=0; i<sitesLeft; ++i ) {
      m_nucleusX[i] = (int)(Math.random() * (double)(m_width * SUBPIXEL_RES) );
      m_nucleusY[i] = ((int)(Math.random() * (double)(m_height * SUBPIXEL_RES / 2) )) + m_height*SUBPIXEL_RES/2;
      m_nucleusT[i] = (int)(Math.random() * (double)(BUBBLE_RATE_MAX-BUBBLE_RATE_MIN) )+BUBBLE_RATE_MIN;
      m_nucleusR[i] = 0;
    }


    // Initialise graphics canvas
    DirectColorModel colModel = new DirectColorModel( 32, 0x00ff0000, 0x0000ff00, 0x000000ff );
    m_memSrc = new MemoryImageSource( m_width, m_height, colModel, m_pixels, 0, m_width );
    m_memSrc.setAnimated( true );
    m_backBufferImage = createImage( m_memSrc );


    enableEvents(AWTEvent.MOUSE_EVENT_MASK);
    enableEvents(AWTEvent.MOUSE_MOTION_EVENT_MASK);
    m_shutDown = false;
    m_paused = true;
  }

/******************************************************************************
 * start() method.                                                            *
 * Called when applet gets focus, becomes visible.                            *
 ******************************************************************************/
  public void start()
  {
    // Create a Thread object, tell it that this owns the run() method which it is to call.
    m_gameThread = new Thread(this);
    if(m_gameThread != null)
    {
      // Tell the Thread object to start.
      m_gameThread.start();
    }

    // Unpause the game
    // Don't need to synchronise, cos it's atomic (I hope)
    m_paused = false;
  }

/******************************************************************************
 * stop() method.                                                             *
 * Called when applet loses focus, becomes invisible.                         *
 ******************************************************************************/
  public void stop()
  {
    // Pause the game
    // Don't need to synchronise, cos it's atomic (I hope)
    m_paused = true;
  }

/******************************************************************************
 * destroy() method.                                                          *
 * Called when applet is shut down. Must destroy the animation thread here.   *
 ******************************************************************************/
  public void destroy()
  {
    // Tell the run() thread to exit.
    // Don't need to synchronise, cos it's atomic (I hope)
    m_shutDown = true;
  }

/******************************************************************************
 * update() method.                                                           *
 * Need to override update because the Applet one clears the screen first.    *
 ******************************************************************************/
  public void update(Graphics g)
  {
    g.drawImage( m_backBufferImage, 0, 0, this );
  }

/******************************************************************************
 * run() method.                                                              *
 * Called by gameThread, effectively once. Kind of a "go" method.             *
 ******************************************************************************/
  public void run()
  {
    Rectangle dirtyRect;
    dirtyRect = new Rectangle(0,0,m_width,m_height);

    // Run loop /////////////////////////////////////////
    while(m_shutDown==false)
    {
      // Draw background image, erasing earlier frame graphics.
//     m_backBufferGraphics.drawImage(m_backgroundImage, 0, 0, this);

      // Draw bubbles
      paintBubbles();
      m_memSrc.newPixels( 0, 0, m_width, m_height ); // Tell mem source that it's data has changed

      // Call component.repaint, which will eventually call this.paint().
      repaint(dirtyRect.x, dirtyRect.y, dirtyRect.width, dirtyRect.height);

      // Move everything on by one frame
      animateNucleii();
      animateBubbles();

      // Sleep for a bit to get down to a reasonable speed.
      try
      {
        // Note that sleep() is static to Thread class, and sleeps the current thread.
        Thread.sleep(30);
      }
      catch(InterruptedException e) {/* Do nothing. */};
    }
    // End of run loop

    // Shut down applet here.
  }

/******************************************************************************
 * paint() method.                                                            *
 * Called when browser wants the applet to draw itself on the screen.         *
 ******************************************************************************/
  public void paint(Graphics g)
  {
      // Nothing
  }


  protected void paintBubbles() {

    // Copy image to subpixel backbuffer
    for( int i=m_width * m_height * SUBPIXEL_RES * SUBPIXEL_RES -1; i>=0; --i ) {
      m_subpixels[i] = -1;
    }

    // Sub pixel rendering of each bubble
    for( int i=0; i<NUM_BUBBLES; ++i ) {

      // Skip dead bubbles
      if( m_bubblesAlive[i] == false ) {
        continue; // TBD try leaving them hanging around the surface
      }

      int r = m_bubblesR[i]; // bubble radius
      int[] t;
      // Find a suitable texture to use for this bubble
      while( true ) {
        t = m_textures[r-1];
        if( t != null ) {
          break;
        }
        ++r;
      } // NB m_textures must have a non-null terminating entry or else this loop will go off into the weeds

      int left = m_bubblesX[i] - r/2;
      int top = m_bubblesY[i] - r/2;

      // Copy the pixels into our subpixel canvas. Alpha is premultiplied!
      int k = 0;
      int rr = r;
      int rrr = r;
      int l = left + top * m_width * SUBPIXEL_RES; // TBD precalc width*SUBPIXEL_RES?

      while( rrr > 0 ) {
        if( l < 0 || l >= m_width*m_height*SUBPIXEL_RES*SUBPIXEL_RES ) {
          ++l; // Try next pixel
          if(--rr == 0) { // Copied from below.
            rr = r;
            --rrr;
            l += m_width * SUBPIXEL_RES - r; // next row
          }
          continue; // TBD need to stop wrapping at left/right edges too
        }
        int a = ~m_subpixels[l];
        int b = ~t[k++]; // TBD pre split textures into colour channels?
        // alpha blend
        int cr = (a & 0xff0000) + (b & 0xff0000);
        if( cr > 0xff0000 ) { cr = 0xff0000; }
        int cg = (a & 0xff00) + (b & 0xff00);
        if( cg > 0xff00 ) { cg = 0xff00; }
        int cb = (a & 0xff) + (b & 0xff);
        if( cb > 0xff ) { cb = 0xff; }
        m_subpixels[l++] = ~(cr | cg | cb);
        if(--rr == 0) { // Copied above also.
          rr = r;
          --rrr;
          l += m_width * SUBPIXEL_RES - r; // next row
        }
      };

    }

    // Copy the subpixel canvas to the pixel canvas
    int p=0,q,qq=0;
    for( int j=0; j<m_height; ++j ) {
      for( int i=0; i<m_width; ++i ) {
        int r=0,g=0,b=0;
        q = qq;
        qq += SUBPIXEL_RES; // Next pixel's worth of subpixels horizontally;
        // Combine subpixels into one pixel
        for( int v=0; v<SUBPIXEL_RES; ++v ) {
          for( int u=0; u<SUBPIXEL_RES; ++u ) {
            r += m_subpixels[q] & 0x00ff0000; // NB this technique will break if SUBPIXEL_RES > 16 cos it will overflow the int.
            g += m_subpixels[q] & 0x0000ff00;
            b += m_subpixels[q] & 0x000000ff;
            ++q;
          }
          q += (m_width-1)*SUBPIXEL_RES; // Next row of subpixels
        }
        r /= (SUBPIXEL_RES * SUBPIXEL_RES); // To get mean value
        g /= (SUBPIXEL_RES * SUBPIXEL_RES); // To get mean value
        b /= (SUBPIXEL_RES * SUBPIXEL_RES); // To get mean value

        // Alpha blend on top of background image
        int pix;
        int cr,cg,cb;
        if( m_whiteBubbles ) { // TBD save cycles by making this decision higher up
          pix = m_imgPixels[p];
          cr = (pix & 0xff0000) + (~r & 0xff0000);
          cg = (pix & 0xff00) + (~g & 0xff00);
          cb = (pix & 0xff) + (~b & 0xff);
        } else {
          pix = ~m_imgPixels[p];
          cr = (pix & 0xff0000) + (~r & 0xff0000);
          cg = (pix & 0xff00) + (~g & 0xff00);
          cb = (pix & 0xff) + (~b & 0xff);
        }

        if( cr > 0xff0000 ) { cr = 0xff0000; }
        if( cg > 0xff00 ) { cg = 0xff00; }
        if( cb > 0xff ) { cb = 0xff; }

        if( m_whiteBubbles ) { // TBD save cycles by making this decision higher up
          m_pixels[p++] = (cr&0x00ff0000) | (cg&0x0000ff00) | (cb&0x000000ff);
        } else {
          m_pixels[p++] = (~cr&0x00ff0000) | (~cg&0x0000ff00) | (~cb&0x000000ff);
        }

      }
      qq += (SUBPIXEL_RES-1) * SUBPIXEL_RES * m_width;
    }
  }

  // Searches for a dead bubble index
  protected int nextFreeBubble() {
    int start = m_freeIndex;

    /*
    if( mouse inside applet ) {
      return -2 and use mouse coordinates as nucleation site
    }
    */
    do {
      ++m_freeIndex;
      if( m_freeIndex == NUM_BUBBLES ) {
        m_freeIndex = 0;
      }

      if( m_bubblesAlive[m_freeIndex] == false ) {
        return m_freeIndex;
      }
    } while( m_freeIndex != start );

    return -1;
  }

  protected void animateNucleii() {
    for( int i=0; i<NUM_NUCLEUS; ++i ) {
      if(m_nucleusR[i] == 0) {
        int b = nextFreeBubble();
        if( b != -1 ) {
          if( m_mouse && i==0 ) {
            m_nucleusR[i] = BUBBLE_RATE_MIN / 2;
            m_bubblesX[b] = m_mouseX;
            m_bubblesY[b] = m_mouseY;
          } else {
            m_nucleusR[i] = m_nucleusT[i];
            m_bubblesX[b] = m_nucleusX[i];
            m_bubblesY[b] = m_nucleusY[i];
          }
          m_bubblesQ[b] = 1;
          m_bubblesR[b] = 1;
          m_bubblesAlive[b] = true;
        }
      } else {
        --m_nucleusR[i];
      }
    }
  }

  protected void animateBubbles() {
    // Placeholder animation algorithm atm
    for( int i=0; i<NUM_BUBBLES; ++i ) {
      //m_bubblesX[i] += (int)(Math.random() * 3.0)*10 - 10; // TBD check if -1 is rounded to 0
      m_bubblesY[i] -= m_bubblesR[i]/2;
      if( m_bubblesY[i] < 0 ) {
        m_bubblesAlive[i] = false;
      } else {
        m_bubblesQ[i] += 1;
        m_bubblesR[i] = m_bubblesQ[i] / 3 + 1;
        if( m_bubblesR[i] >= BUBBLE_DIAM ) {
          m_bubblesR[i] = BUBBLE_DIAM-1;
        }
      }
    }
  }


  public void readTextures( String datFile ) {
    try {
      // Open data file from server
      URL url = new URL( getDocumentBase(), datFile );

      InputStream in = url.openStream();
      byte[] sizeBuf = new byte[1];

      int offset = 0;
      int size;

      while(true) {
        in.read(sizeBuf);
        size = sizeBuf[0] & 0xff;
        if( size == 0 ) break;

        if( size > BUBBLE_DIAM ) {
          System.out.println("Bubble size too big!" + size);
          return;
        }

        byte[] pixbytes = new byte[ size * size * 3 ];
        m_textures[size-1] = new int[ size * size ];
        int read = in.read( pixbytes, 0, size * size * 3 );

        // Convert bytes to pixel values
        int k=0;
        for( int j=0; j<size*size*3; j+=3 ) {
	    int pval = (pixbytes[j] & 0xff)<<16 | (pixbytes[j+1]&0xff)<<8 | (pixbytes[j+2]&0xff);
          m_textures[size-1][k++] = pval;
        }
      }

      // Close file on server
      // Done by going out of scope
    } catch( IOException e ) {
      showStatus("Error Loading Textures!");
      while(true); // Infinite loop
      // Nothing
    }

  }


/******************************************************************************
 * processMouseEvent(MouseEvent e) method.                                    *
 * The mouse down event handler override.                                     *
 ******************************************************************************/
  public void processMouseEvent(MouseEvent e)
  {
    if( e.getID() == e.MOUSE_EXITED ) {
      m_mouse = false;
    } else if( e.getID() == e.MOUSE_PRESSED ) {
      try {
        AppletContext ac = getAppletContext();
        ac.showDocument( new URL("http://www.logicmill.com"), "_self" );
      }
      catch( MalformedURLException ex ) {
        // nothing
      }
    }
  }

/******************************************************************************
 * processMouseMotionEvent(MouseMotionEvent e) method.                        *
 * The mouse motino event handler override.                                   *
 ******************************************************************************/
  public void processMouseMotionEvent( MouseEvent e )
  {
    m_mouse = true;
    m_mouseX = e.getX() * SUBPIXEL_RES;
    m_mouseY = e.getY() * SUBPIXEL_RES;
  }

}
