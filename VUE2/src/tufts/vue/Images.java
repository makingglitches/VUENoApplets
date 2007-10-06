/*
 * -----------------------------------------------------------------------------
 *
 * <p><b>License and Copyright: </b>The contents of this file are subject to the
 * Mozilla Public License Version 1.1 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License
 * at <a href="http://www.mozilla.org/MPL">http://www.mozilla.org/MPL/.</a></p>
 *
 * <p>Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 * the specific language governing rights and limitations under the License.</p>
 *
 * <p>The entire file consists of original code.  Copyright &copy; 2003, 2004 
 * Tufts University. All rights reserved.</p>
 *
 * -----------------------------------------------------------------------------
 */

package tufts.vue;

import tufts.Util;

import java.util.*;
import java.lang.ref.*;
import java.net.URL;
import java.net.URI;
import java.net.URLConnection;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.awt.Image;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;
import javax.imageio.*;
import javax.imageio.event.*;
import javax.imageio.stream.*;

/**
 *
 * Handle the loading of images in background threads, making callbacks to deliver
 * results to multiple listeners that can be added at any time during the image fetch,
 * and caching (memory and disk) with a URI key, using a HashMap with SoftReference's
 * for the BufferedImage's so if we run low on memory they just drop out of the cache.
 *
 * @version $Revision: 1.28 $ / $Date: 2007-10-06 03:49:25 $ / $Author: sfraize $
 * @author Scott Fraize
 */
public class Images
{
    private static final org.apache.log4j.Logger Log = org.apache.log4j.Logger.getLogger(Images.class);

    public static VueAction ClearCacheAction = new VueAction("Empty Image Cache") {
            public void act() { Cache.clear(); }
        };
    
    private static CacheMap Cache = new CacheMap();

    
    /**
     * Calls to Images.getImage must pass in a Listener to get results.
     * The first argument to all the callbacks is the original object
     * passed in to getImage as the imageSRC.
     */
    public interface Listener {
        /** If image is already cached, this will NOT be called -- is only called from an image loading thread. */
        void gotImageSize(Object imageSrc, int w, int h, long byteSize);
        /** If byte-tracking is enabled on the input source, this will be called periodically during loading */
        void gotBytes(Object imageSrc, long bytesSoFar);
        /** Will be called immediately in same thread if image cached, later in different thread if not. */
        void gotImage(Object imageSrc, Image image, int w, int h);
        /** If there is an exception or problem loading the image, this will be called */
        void gotImageError(Object imageSrc, String msg);
    }


    /**
     * Fetch the given image.  If it's cached, listener.gotImage is called back immediately
     * in the current thread.  If not, the image is fetched asynchronously, and the
     * callbacks are made later from a special image loading thread.
     *
     * @param imageSRC - anything that might be converted to an image: a Resource, File, URL, InputStream, etc.
     *
     * @param listener - the Images.Listener to callback.  If null, the result
     * of the call would be only to ensure the given image is cached.
     *
     * @return true if the result is immediately available: the image was cached or there was an immediate error
     **/
    
    public static boolean getImage(Object imageSRC, Images.Listener listener)
    {
        try {
            if (getCachedOrLoad(imageSRC, listener) == null)
                return false;
        } catch (Throwable t) {
            if (DEBUG.IMAGE) tufts.Util.printStackTrace(t);
            if (listener != null)
                listener.gotImageError(imageSRC, t.toString());
        }
        return true;
    }
    
    
    /** synchronously retrive an image from the given data source: e.g., a Resource, File, URL, InputStream, etc */
    public static BufferedImage getImage(Object imageSRC)
    {
        try {
            return getCachedOrLoad(imageSRC, null);
        } catch (Throwable t) {
            if (DEBUG.IMAGE) tufts.Util.printStackTrace(t);
            Log.error("getImage " + imageSRC + ": " + t);
            return null;
        }
    }

    private static class CacheEntry {
        private Reference imageRef;
        private File file;

        /** image should only be null for startup init with existing cache files */
        CacheEntry(BufferedImage image, File cacheFile)
        {
            if (image == null && cacheFile == null)
                throw new IllegalArgumentException("CacheEntry: at least one of image or file must be non null");
            if (image != null)
                this.imageRef = new SoftReference(image);
            this.file = cacheFile;
            if (DEBUG.IMAGE) out("new " + this);
        }

        BufferedImage getImage() {

            // if don't even have a ref, this was for an init-time persitent cache file
            if (imageRef == null)
                return null;

            BufferedImage image = (BufferedImage) imageRef.get();
            // will be null if was cleared
            if (image == null) {
                if (DEBUG.Enabled) out("GC'd: " + file);
                return null;
            } else
                return image;
        }

        File getFile() {
            return file;
        }

        void clear() {
            if (imageRef != null)
                imageRef.clear();
        }

        public String toString() {
            return "CacheEntry[" + tag(getImage()) + "; file=" + file + "]";
        }
    }


    /*
     * Not all HashMap methods covered: only safe to use
     * the onces explicity implemented here.
     */
    private static class CacheMap extends HashMap {

        public synchronized Object get(Object key) {
            return super.get(key);
        }
        
        public synchronized boolean containsKey(Object key) {
            return super.containsKey(key);
        }
        
        public synchronized Object put(Object key, Object value) {
            return super.put(key, value);
        }

        public synchronized Object remove(Object key) {
            return super.remove(key);
        }
        

        // for now, only clears memory cache
        public synchronized void clear() {
            Iterator i = values().iterator();
            while (i.hasNext()) {
                Object entry = i.next();

                // may be a Loader: todo: may want to kill thread if it is
                // Especially: if we go off line, Loaders created
                // immediately after that (or during) tend to hang forever.
                // Loaders created once the OS knows we're offline
                // will usually fail immediately with "no route to host",
                // but even after going back online, and other images
                // load, the originally hung Loader's won't die...
                // So at least an image-cache should kill them.
                
                if (entry instanceof CacheEntry) {
                    CacheEntry ce = (CacheEntry) entry;
                    ce.clear();
                    if (ce.getFile() == null)
                        i.remove();
                } else {
                    // Interrupt may not be good enough: if blocked on non-async IO
                    // (non-channel IO, e.g., "regular"), this can have no
                    // effect.  Turns out using stop doesn't help even in this
                    // case.
                    ((Loader)entry).stop();
                    //((Loader)entry).interrupt();
                }
            }

            //super.clear();
        }
        
    }

    private static URI makeKey(URL u) {
        try {
            return new URI(u.getProtocol(),
                           u.getUserInfo(),
                           u.getHost(),
                           u.getPort(),
                           //u.getAuthority(),
                           u.getPath(),
                           u.getQuery(),
                           u.getRef()).normalize();
        } catch (Throwable t) {
            Util.printStackTrace(t, "can't make URI cache key from URL " + u);
        }
        return null;
    }

    private static URI makeKey(File file) {
        try {
            return file.toURI().normalize();
        } catch (Throwable t) {
            Util.printStackTrace(t, "can't make URI cache key from file " + file);
        }
        return null;
    }

    private static String keyToCacheFileName(URI key)
        throws java.io.UnsupportedEncodingException
    {
        //return key.toASCIIString();
        return java.net.URLEncoder.encode(key.toString(), "UTF-8");
    }
    
    private static URI cacheFileNameToKey(String name) 
    {
        try {
            return new URI(java.net.URLDecoder.decode(name, "UTF-8"));
            //return new URL(java.net.URLDecoder.decode(name, "UTF-8"));
        } catch (Throwable t) {
            if (DEBUG.Enabled)
                tufts.Util.printStackTrace(t);
            return null;
        }

    }


    // TODO: Using a URL as the key is very slow: it actually does host name resolution
    // for the IP address to do compares!


    private static class ImageSource {
        final Object original;  // anything plausably covertable image source (e.g. a Resource, URL, File, stream)
        final Resource resource;// if original was a resource, it goes here.
        final URI key;          // Unique key for caching
        Object readable;        // the readable image source (not a Resource, and URL's converted to stream before ImageIO)
        File cacheFile;         // If later stored in a file cache, is marked here.

        ImageSource(Object original) {
            this.original = original;

            if (original instanceof Resource) {
                Resource r = (Resource) original;
                if (r.getSpec().startsWith("/"))  {
                    // todo: Also if this is a file:/ URL (maybe slight performance increase)
                    File file = new java.io.File(r.getSpec());
                    this.readable = file;
                } else {
                    //if (DEBUG.IMAGE) tufts.Util.printStackTrace("converting Resource to IMAGE " + r);
                    if (DEBUG.IMAGE) out("converting Resource to IMAGE " + r);
                    this.readable = r.getImageSource();
                }
                this.resource = r;
            } else if (original instanceof java.net.URL) {
                this.readable = (java.net.URL) original;
                this.resource = null;
            } else
                this.resource = null;

            
            if (readable instanceof java.net.URL) {
                URL url = (URL) readable;
                this.key = makeKey(url);
                /*
                if (DEBUG.IMAGE && DEBUG.META) {
                    Util.dumpURL(url);
                    
                    /* the toURI() method is new in Java 1.5.  Can we use the equivalent Java 1.4
                     * technique to avoid Java version problems? - peter 16-Jun-06 
                    try {Util.dumpURI(url.toURI());} catch (Throwable t) { out(t); }
                    *
                    try {Util.dumpURI(new URI(url.toString()));} catch (Throwable t) { out(t); }
                }
                */
                if ("file".equals(key.getScheme())) {
                    
                    // If this is a Win32 file://C:\foo\bar path, we must include the
                    // URL "authority", which is where the "C:" is (it's not included in
                    // the path).  This is tested on Win2K & WinXP as of June 2006.
                    
                    final String driveLetter = url.getAuthority();
                    final String fullPath;
                    if (driveLetter != null)
                        fullPath = driveLetter + url.getFile();
                    else
                        fullPath = url.getFile();
                    this.readable = new File(fullPath);
                }
            } else if (readable instanceof java.io.File) {
                this.key = makeKey((File) readable);
            } else
                this.key = null; // will not be cacheable
            
        }

        public String toString() {
            String s = tag(original);
            if (readable != original)
                s += "; readable=[" + tag(readable) + "]";
            if (cacheFile != null)
                s += "; cache=" + cacheFile;
            return s;
        }

    }

    /**
     * Using a relay system, as opposed to say a list of listeners maintained by the
     * Loader, allows the image loading code to not care if there is a single listener
     * or multiple listeners, which is handy in the case where the result is cached and
     * we don't even create a loader: we just callback the listener immediately in the
     * same thread.  But when a Loader is created, it can create ListenerRelay's to
     * relay results down the chain, starting with it's special LoaderRelayer to relay
     * partial results to listeners added in the middle of an image fetch, and again,
     * the image loading code doesn't need to know about this: it just has a single
     * listener.
     *
     * Performance-wise, there is rarely ever more than a single relayer object
     * created (covering two listeners for the same image load).
     *
     * This is also a handy place for diagnostics.
     */
    private static class ListenerRelay implements Listener {
        protected final Listener head;
        protected Listener tail;

        
        ListenerRelay(Listener l0, Listener l1) {
            this.head = l0;
            this.tail = l1;
        }
        ListenerRelay(Listener l0) {
            this(l0, null);
        }

        public void gotImageSize(Object imageSrc, int w, int h, long byteSize) {
            if (DEBUG.IMAGE) out("relay SIZE to head " + tag(head) + " " + imageSrc);
            head.gotImageSize(imageSrc, w, h, byteSize);
            if (tail != null) {
                if (DEBUG.IMAGE) out("relay SIZE to tail " + tag(tail) + " " + imageSrc);
                tail.gotImageSize(imageSrc, w, h, byteSize);
            }
        }
        public void gotBytes(Object imageSrc, long bytesSoFar) {
            if (DEBUG.IMAGE) out("relay BYTES to head " + tag(head) + " " + imageSrc);
            head.gotBytes(imageSrc, bytesSoFar);
            if (tail != null) {
                if (DEBUG.IMAGE) out("relay BYTES to tail " + tag(tail) + " " + imageSrc);
                tail.gotBytes(imageSrc, bytesSoFar);
            }
        }
        public void gotImage(Object imageSrc, Image image, int w, int h) {
            if (DEBUG.IMAGE) out("relay IMAGE to head " + tag(head) + " " + imageSrc);
            head.gotImage(imageSrc, image, w, h);
            if (tail != null) {
                if (DEBUG.IMAGE) out("relay IMAGE to tail " + tag(tail) + " " + imageSrc);
                tail.gotImage(imageSrc, image, w, h);
            }
        }
        public void gotImageError(Object imageSrc, String msg) {
            if (DEBUG.IMAGE) out("relay ERROR to head " + tag(head) + " " + imageSrc);
            head.gotImageError(imageSrc, msg);
            if (tail != null) {
                if (DEBUG.IMAGE) out("relay ERROR to tail " + tag(tail) + " " + imageSrc);
                tail.gotImageError(imageSrc, msg);
            }
        }

        boolean hasListener(Listener l) {
            if (head == l || tail == l)
                return true;
            if (tail instanceof ListenerRelay)
                return ((ListenerRelay)tail).hasListener(l);
            else
                return false;
        }

    }

    /**
     * Track what's been delivered, to send to listeners that are added when
     * partial results have already been delivered.
     */
    private static class LoaderRelayer extends ListenerRelay
    {
        private final ImageSource imageSRC;
        
        private Image image = null;
        private int width = -1;
        private int height = -1;
        private long byteSize;
        private long bytesSoFar;
        private String errorMsg = null;
        
        
        LoaderRelayer(ImageSource is, Listener firstListener) {
            super(firstListener, null);
            imageSRC = is;
        }

        @Override
        public synchronized void gotImageSize(Object imageSrc, int w, int h, long byteSize) {
            this.width = w;
            this.height = h;
            this.byteSize = byteSize;
            super.gotImageSize(imageSrc, w, h, byteSize);
        }

        @Override
        public synchronized void gotBytes(Object imageSrc, long bytesSoFar) {
            this.bytesSoFar = bytesSoFar;
            super.gotBytes(imageSrc, bytesSoFar);
        }
        
        @Override
        public synchronized void gotImage(Object imageSrc, Image image, int w, int h) {
            this.image = image;
            super.gotImage(imageSrc, image, w, h);

        }
        
        @Override
        public synchronized void gotImageError(Object imageSrc, String msg) {
            this.errorMsg = msg;
            super.gotImageError(imageSrc, msg);

        }

        synchronized void addListener(Listener newListener)
        {
            if (hasListener(newListener)) {
                if (DEBUG.IMAGE) out("Loader; ALREADY A LISTENER FOR THIS IMAGE: " + tag(newListener));
                return; 
            }
            
            // Deliver any results we've already got.  It's theoretically possible
            // for this to happen even after we have all our results, if the image
            // completed between the time we found the Loader in the cache, and the
            // time the requestor was added as a listener.
            
            deliverPartialResults(newListener);
            
            if (tail == null)  {
                tail = newListener;
            } else {
                tail = new ListenerRelay(tail, newListener);
            }

        }
        
        private void deliverPartialResults(Listener l)
        {
            if (DEBUG.IMAGE) out("DELIVERING PARTIAL RESULTS TO: " + tag(l));
            
            if (width > 0)
                l.gotImageSize(imageSRC.original, width, height, byteSize);

            if (bytesSoFar > 0)
                l.gotBytes(imageSRC.original, bytesSoFar);

            if (image != null)
                l.gotImage(imageSRC.original, image, width, height);

            if (errorMsg != null) 
                l.gotImageError(imageSRC.original, errorMsg);

            if (DEBUG.IMAGE) out("DONE DELIVERING PARTIAL RESULTS TO: " + tag(l));
            
        }

    }
    
    

    /**
     * A thread for loading a single image.  Images.Listener results are delievered
     * from this thread (unless the image was already cached).
     */
    static class Loader extends Thread {
        private static int LoaderCount = 0;
        private final ImageSource imageSRC; 
        private final LoaderRelayer relay;


        /**
         * @param src must be any valid src *except* a Resource
         * @param resource - if this is tied to a resource to update with meta-data after loading
         */
        Loader(ImageSource imageSRC, Listener l) {
            super("VUE-ImageLoader" + LoaderCount++);
            if (l == null)
                Log.warn(this + "; nobody listening: image will be quietly cached: " + imageSRC);
            this.imageSRC = imageSRC;
            this.relay = new LoaderRelayer(imageSRC, l);
            setDaemon(true);
            //setPriority(NORM_PRIORITY - 1);
        }

        public void run() {
            if (DEBUG.IMAGE || DEBUG.THREAD) out("Loader: load " + imageSRC + " kicked off");
            BufferedImage bi = loadImage(imageSRC, relay);
            if (DEBUG.IMAGE || DEBUG.THREAD) out("Loader: load returned, result=" + tag(bi));
        }

        void addListener(Listener newListener) {
            relay.addListener(newListener);
        }
    }


    
    /**
     * @return Image if cached or listener is null, otherwise makes callbacks to the listener from
     * a new thread.
     */
    private static BufferedImage getCachedOrLoad(Object _imageSRC, Images.Listener listener)
        throws java.io.IOException, java.lang.InterruptedException
    {
        final ImageSource imageSRC = new ImageSource(_imageSRC);

        if (DEBUG.IMAGE) {
            System.out.println("\n");
            out("FETCHING IMAGE SOURCE " + imageSRC + " for " + tag(listener));
        }


        Object fetchResult;
        BufferedImage cachedImage = null;
        
        synchronized (Cache) {
            fetchResult = getCacheFetchResult(imageSRC, listener);
        }

        if (fetchResult == IMAGE_LOADER_STARTED)
            return null;

        if (fetchResult instanceof Loader) {
            Loader loader = (Loader) fetchResult;

            if (listener != null) {
                if (DEBUG.IMAGE) out("Adding us as listener to existing Loader");
                // TODO CRITICAL: above is last message we're seeing before
                // an apparent deadlock when rapidly dragging search result
                // resources onto map: is this the same deadlock we already "fixed" ??
                loader.addListener(listener);
                return null;
            }
                
            // We had no listener, so run synchronous & wait on existing loader thread to die:
            // We can't have a cache-lock when we do this.
            
            out("Joining " + tag(loader) + "...");
            loader.join();
            out("Join of " + tag(loader) + " completed, cache has filled.");
            
            // Note we get here only in one rare case: there was an entry in the
            // cache that was already loading on another thread, and somebody new
            // requested the image that did NOT have a listener, so we joined the
            // existing thread and waited for it to finish (with no listener, we
            // have to run synchronous).
            
            // So now that we've waited, we should be guarnateed to have a full
            // Image result in the cache at this point.
            
            // Note: theoretically, the GC could have cleared our SoftReference
            // betwen loading the cache and now, tho this may never happen.
            
            cachedImage = ((CacheEntry)Cache.get(imageSRC.key)).getImage();
            if (cachedImage == null)
                Log.warn("Zealous GC: image tossed immediately " + imageSRC);

        } else if (fetchResult instanceof BufferedImage) {
            cachedImage = (BufferedImage) fetchResult;
        }


        if (cachedImage != null) {
            if (listener != null) {
                // immediately callback the listener with the result
                listener.gotImage(imageSRC.original,
                                  cachedImage,
                                  cachedImage.getWidth(),
                                  cachedImage.getHeight());
            }
            return cachedImage;
        }

        // We had no image, and no Loader was started: this should only
        // happen if there was no listener for the Loader, tho
        // we allow the sync load to go ahead just in case.
        // (Could get here due to an over-zealous GC).

        if (listener != null)
            Util.printStackTrace("had a listener, but no Loader created: backup syncrhonous loading for " + imageSRC);

        if (DEBUG.IMAGE) out("synchronous load of " + imageSRC);
        
        // load the image and don't return until we have it
        return loadImage(imageSRC, listener);
    }
    

    private static final Object IMAGE_LOADER_STARTED = "<image-loader-created>";

    /**
     * This method should only be called in a cache lock.  It has all sorts of side
     * effects to the cache.  At the end of this call, we usually know there is
     * something in the cache for the given imageSRC.key -- either we found the image
     * already there, or we found a Loader thread already started there, or we put and
     * started a new Loader thread there.  Unless there was no listener and nothing in
     * the cache, in which case the image needs to be loaded synchronously (and we
     * return null).
     *
     * This method also deals with cache cleanup: if an entry is found to be
     * empty (it has no disk file, and it's image has been GC'd), the entry
     * is removed.
     *
     * @return If an Image, we had the image in the cache immediately availble.  If a
     * Loader thread object, the image is loading, and we should become and additional
     * listener (if there is a listener given), or wait for the Loader to die to get
     * it's results.  If the special value IMAGE_LOADER_STARTED is returned, there is
     * nothing to do be done for now -- just wait for the callbacks to the listener if
     * one was provided.
     */
    private static Object getCacheFetchResult(ImageSource imageSRC, Images.Listener listener)
    {
        Object entry;
        
        if (imageSRC.key != null && (entry = Cache.get(imageSRC.key)) != null) {
            if (DEBUG.IMAGE) out("found cache entry for key " + tag(imageSRC.key) + ": " + entry);
                
            if (entry instanceof Loader) {
                if (DEBUG.IMAGE) out("Image is loading into the cache via already existing Loader...");
                return entry;
            }

            // Entry is not a Loader, so it must be a regular CacheEntry
            // We still may not have an image tho: it may be been
            // garbage collected, or the entry may actually be for a file
            // on disk.

            CacheEntry ce = (CacheEntry) entry;
            BufferedImage cachedImage = ce.getImage();
                     	
            // if we have the image, we're done (it was loaded this runtime, and not GC'd)
            // if not, either it was GC'd, or it's a cache file entry from the persistent
            // cache -- in either case, there is a file on disk -- mark it in the imageSRC,
            // and the loader will notice it and use it.
                    
            boolean emptyEntry = true;

            if (cachedImage != null) {
                emptyEntry = false;
            } else if (ce.getFile() != null) {
                if (ce.file.canRead()) {
                    imageSRC.cacheFile = ce.file;
                    emptyEntry = false;
                } else
                    Log.warn("cache file no longer available: " + ce.file);
            }

            if (emptyEntry) {
                // there is a cache entry with no image OR file: this could only
                // happen if the disk cache is not operating, and the memory
                // image was garbage collected: we need to remove this entry
                // from the cache completely and start from scratch:
                if (DEBUG.Enabled) out("REMOVING FROM CACHE: " + imageSRC);
                Cache.remove(imageSRC.key);
            }

            if (cachedImage != null)
                return cachedImage;

            // The was an entry in the cache, but it was of no use:
            
            // We get here in the following cases:
            // (1) We had the image, but is was GC'd -- we're going back to the cache file
            // (2) We had the image, but is was GC'd -- original was on disk: go back to that
            // (3) We had the image, but is was GC'd, and disk cache not working: reload original from network
            // (4) We never had the image, but it is in disk cache: go get it
            // (5) unlikely case case of zealous GC: reload original

            // Note that original image sources that were on disk are NOT moved
            // to the disk cache, and CacheEntry.file should always be null for those.

        }            

        // Nothing was in the cache: we must go get it.
        // If we have a listener, create a thread to do this.
        // If we don't, do now and don't return until we have the result.
        
        if (listener != null) {
            Loader newLoader = new Loader(imageSRC, listener);
            Cache.put(imageSRC.key, newLoader);
            newLoader.start();
            return IMAGE_LOADER_STARTED;
        }

        // With no listener, and nothing found in the cache, this
        // image will need to be loaded immediately in the current thread.
        return null;
    }
    

    static class ImageException extends Exception {
        ImageException(String s) {
            super(s);
        }
    }
    static class DataException extends ImageException {
        DataException(String s) {
            super(s);
        }
    }

    /** An wrapper for readAndCreateImage that deals with exceptions, and puts successful results in the cache */
    private static BufferedImage loadImage(ImageSource imageSRC, Images.Listener listener)
    {
        BufferedImage image = null;

        if (imageSRC.resource != null)
            imageSRC.resource.getProperties().holdChanges();
        
        try {
            image = readAndCreateImage(imageSRC, listener);
        } catch (Throwable t) {
            
            if (DEBUG.IMAGE) tufts.Util.printStackTrace(t);

            Cache.remove(imageSRC.key);
            
            if (listener != null) {
                String msg;
                if (t instanceof java.io.FileNotFoundException)
                    msg = "Not Found: " + t.getMessage();
                else if (t instanceof java.net.UnknownHostException)
                    msg = "Unknown Host: " + t.getMessage();
                else if (t instanceof ThreadDeath)
                    msg = "interrupted";
                else if (t.getMessage() != null && t.getMessage().length() > 0)
                    msg = t.getMessage();
                else
                    msg = t.toString();

                // this is the one place we deliver caught exceptions
                // during image loading:
                listener.gotImageError(imageSRC.original, msg);

                Log.warn("Image source: " + imageSRC + ": " + t);
            }

            if (imageSRC.resource != null)
                imageSRC.resource.getProperties().releaseChanges();
        }

        // TODO opt: if this items was loaded from the disk cache, we're needlessly
        // replacing the existing CacheEntry with a new one, instead of
        // updating the old with the new in-memory image buffer.

        if (image != null) {
            File permanentCacheFile = null;
            if (imageSRC.cacheFile != null)
                permanentCacheFile = ensurePermanentCacheFile(imageSRC.cacheFile);
            
            if (DEBUG.IMAGE) out("getting cache lock for storing result");
            Cache.put(imageSRC.key, new CacheEntry(image, permanentCacheFile));
        }

        return image;
        
    }

    // todo: this probably wants to move to a resource impl class
    private static void setDateValue(Resource r, String name, long value) {
        if (value > 0)
            r.setProperty(name, new java.util.Date(value).toString());
        // todo: set raw value for compares, but allow prop displayer to convert it?
        // or put a raw Date object in there?
    }

    // TODO: standardize on VUE synthesized meta-data:
    // content.type:    (content-type / mime-type -- from URL & File)
    // content.size:    (file or URL on-disk content size)
    // content.modified: (file / URL last modified)
    // content.updated:  retrieved / URL "date", will always be the read time (image load) for local files
    //  (accessed? asOf? retrieved?)
    // content.source: e.g., "Local Disk", "Internet" (not Web, as confuses with FTP), "Black Ships"
            
    // todo: these constants to move to Resource.java
    public static final String CONTENT_SIZE = "Content.size";
    public static final String CONTENT_TYPE = "Content.type";
    public static final String CONTENT_MODIFIED = "Content.modified";
    public static final String CONTENT_ASOF = "Content.asOf";
    public static final String CONTENT_SOURCE = "Content.source";

    // todo: this probably wants to move to a resource impl class
    private static void setResourceMetaData(Resource r, java.net.URLConnection uc) {

//         r.getProperties().holdChanges();
//         try {
        
            long len = uc.getContentLength();
            //r.setProperty("url.contentLength", len);
            r.setProperty(CONTENT_SIZE, len); // todo: be sure to update later from cache file size for correctness
            String ct = uc.getContentType();
            //r.setProperty("url.contentType", ct);
            r.setProperty(CONTENT_TYPE, ct);
            if (DEBUG.Enabled && ct != null && !ct.toLowerCase().startsWith("image")) {
                Util.printStackTrace("FYI: NON IMAGE CONTENT TYPE [" + ct + "] for " + r);
            }
            setDateValue(r, "URL.expires", uc.getExpiration());
            //setDateValue(r, "url.date", uc.getDate());
            setDateValue(r, CONTENT_ASOF, uc.getDate()); // should probably ignore this an generate ourselves
            //setDateValue(r, "url.lastModified", uc.getLastModified());
            setDateValue(r, CONTENT_MODIFIED, uc.getLastModified());
            
//         } catch (Throwable t) {
//             Util.printStackTrace(t);
//         } finally {
//             r.getProperties().releaseChanges();
//         }
                      
    }
    
    // todo: this probably wants to move to a resource impl class
    private static void setResourceMetaData(Resource r, File f) {
//         r.getProperties().holdChanges();
//         try {
        
            //r.setProperty("file.size", f.length());
            r.setProperty(CONTENT_SIZE, f.length());
            //setDateValue(r, "file.lastModified", f.lastModified());
            setDateValue(r, CONTENT_MODIFIED, f.lastModified());

            //r.setProperty(CONTENT_TYPE, java.net.URLConnection.guessContentTypeFromName(f.getName()));
            // todo: also URLConnection.guessContentTypeFromStream (could use in FileBackedImageInputStream)
                
//         } finally {
//             r.getProperties().releaseChanges();
//         }
            
    }

    private static File makeTmpCacheFile(URI key)
        throws java.io.UnsupportedEncodingException
    {
        String cacheName = "." + keyToCacheFileName(key);
        File cacheDir = getCacheDirectory();
        File file = null;
        if (cacheDir != null) {
            file = new File(getCacheDirectory(), cacheName);
            try {
                if (!file.createNewFile())
                    Log.debug("cache file already exists: " + file);
            } catch (java.io.IOException e) {
                Util.printStackTrace(e, "can't create tmp cache file " + file);
                //VUE.Log.warn(e.toString());
                return null;
            }
            if (!file.canWrite()) {
                Log.warn("can't write cache file: " + file);
                return null;
            }
            if (DEBUG.IMAGE) out("got tmp cache file " + file);
        }
        return file;
    }

    private static File ensurePermanentCacheFile(File file)
    {
        try {
            // chop off the initial "." to make permanent
            // If doesn't start with a dot, this is one of our existing cache
            // files: nothing to do.
            String tmpName = file.getName();
            String permanentName;
            if (tmpName.charAt(0) == '.') {
                permanentName = tmpName.substring(1);
            } else {
                // it's already permanent: we must have loaded this from our cache originally
                return file;
            }
            File permanentFile = new File(file.getParentFile(), permanentName);
            if (file.renameTo(permanentFile))
                return permanentFile;
        } catch (Throwable t) {
            tufts.Util.printStackTrace(t, "Unable to create permanent cache file from tmp " + file);
        }
        return null;
    }
    

    private static File CacheDir;
    private static File getCacheDirectory()
    {
        if (CacheDir == null) {
            File dir = VueUtil.getDefaultUserFolder();
            CacheDir = new File(dir, "cache");
            if (!CacheDir.exists()) {
                Log.debug("creating cache directory: " + CacheDir);
                if (!CacheDir.mkdir())
                    Log.warn("couldn't create cache directory " + CacheDir);
            } else if (!CacheDir.isDirectory()) {
                Log.warn("couldn't create cache directory (is a file) " + CacheDir);
                return CacheDir = null;
            }
            Log.debug("Got cache directory: " + CacheDir);
        }
        return CacheDir;
    }


    public static void loadDiskCache()
    {
        File dir = getCacheDirectory();
        if (dir == null)
            return;

        Log.debug("listing disk cache...");
        File[] files = dir.listFiles();
        Log.debug("listing disk cache: done; entries=" + files.length);
        
        synchronized (Cache) {
            for (int i = 0; i < files.length; i++) {
                File file = files[i];
                String name = file.getName();
                if (name.charAt(0) == '.')
                    continue;
                //out("found cache file " + file);
                URI key = cacheFileNameToKey(name);
                if (DEBUG.IMAGE && DEBUG.META) out("made cache key: " + key);
                if (key != null)
                    Cache.put(key, new CacheEntry(null, file));
            }
        }
    }


    
    /**
     * @param imageSRC - see ImageSource ("anything" that we can get an image data stream from)
     * @param listener - an Images.Listener: if non-null, will be issued callbacks for size & completion
     * @return the loaded image, or null if none found
     */
    private static boolean FirstFailure = true;
    private static BufferedImage readAndCreateImage(ImageSource imageSRC, Images.Listener listener)
        throws java.io.IOException, ImageException
    {
        if (DEBUG.IMAGE) out("READING " + imageSRC);

        InputStream urlStream = null; // if we create one, we need to keep this ref to close it later
        File tmpCacheFile = null; // if we create a tmp cache file, it will be put here

        int dataSize = -1;
        
        if (imageSRC.cacheFile != null) {
            // just point us at the cache file: ImageIO will create the input stream
            imageSRC.readable = imageSRC.cacheFile;
            if (DEBUG.IMAGE) out("reading cache file: " + imageSRC.cacheFile);

            // note: can get away with this because imageSRC.resource will
            // be null if this is for a preview icon, so don't need to worry
            // about getting wrong size todo: a hack anyway -- include
            // in clean-up of meta-data setting

            if (imageSRC.resource != null) {
                imageSRC.resource.setProperty(CONTENT_SIZE, imageSRC.cacheFile.length());
                // java has no creation date for Files!  Well, last modified good enough...
                setDateValue(imageSRC.resource, CONTENT_ASOF, imageSRC.cacheFile.lastModified());
            }
        
        } else if (imageSRC.readable instanceof java.net.URL) {
            final URL url = (URL) imageSRC.readable;
            final String asText = url.toString();
            URL cleanURL = url;

            if (asText.indexOf(' ') > 0) {
                // Added 2007-09-20 SMF -- Sakai HTTP server is rejecting spaces in the URL path.
                try {
                    cleanURL = new URL(asText.replaceAll(" ", "%20"));
                } catch (Throwable t) {
                    Util.printStackTrace(t, asText);
                    return null;
                }
            }

            int tries = 0;
            boolean success = false;

            final boolean debug = DEBUG.IMAGE || DEBUG.IO;

            final Map<String,String> sessionKeys = UrlAuthentication.getRequestProperties(url);
            
            do {
                if (debug) out("opening URLConnection... (sessionKeys " + sessionKeys + ")");
                final URLConnection conn = cleanURL.openConnection();

                if (sessionKeys != null) {
                    for (Map.Entry<String,String> e : sessionKeys.entrySet()) {
                        if (debug) System.out.println("\tHTTP request[" + e.getKey() + ": " + e.getValue() + "]");
                        conn.setRequestProperty(e.getKey(), e.getValue());
                    }
                }

                if (debug) {
                    out("got URLConnection: " + conn);
                    final Map<String,List<String>> rp = conn.getRequestProperties();
                    for (Map.Entry<String,List<String>> e : rp.entrySet()) {
                        System.out.println("\toutbound HTTP header[" +e.getKey() + ": " + e.getValue() + "]");
                    }
                }
                
                if (imageSRC.resource != null) {
                    dataSize = conn.getContentLength();
                    try {
                        setResourceMetaData(imageSRC.resource, conn);
                    } catch (Throwable t) {
                        // Don't fail if a problem with meta data: still give
                        // a chance for the content to work...
                        Util.printStackTrace(t, "URLConnection Meta Data Failure");
                        //imageSRC.resource.setProperty("MetaDataFailure", t.toString());
                    }
                }
                
                if (debug) out("opening URL stream...");
                urlStream = conn.getInputStream();
                if (debug) out("got URL stream");

                if (debug) {
                    out("Connected. Headers---");
                    final Map<String,List<String>> headers = conn.getHeaderFields();
                    for (Map.Entry<String,List<String>> e : headers.entrySet()) {
                        System.out.println(e.getKey() + ": " + e.getValue());
                    }
                }
                

                if (debug) {
                    out("Connected. Headers---");
                    final Map<String,List<String>> headers = conn.getHeaderFields();
                    for (Map.Entry<String,List<String>> e : headers.entrySet()) {
                        System.out.println(e.getKey() + ": " + e.getValue());
                    }
                }
                

                tmpCacheFile = makeTmpCacheFile(imageSRC.key);
                imageSRC.cacheFile = tmpCacheFile;  // will be made permanent if no errors

                if (imageSRC.cacheFile != null) {
                    try {
                        imageSRC.readable = new FileBackedImageInputStream(urlStream, tmpCacheFile, listener);
                        success = true;
                    } catch (Images.DataException e) {
                        Log.error(imageSRC + ": " + e);
                        if (++tries > 1) {
                            tufts.Util.printStackTrace(e);
                            throw e;
                        } else {
                            Log.info("second try for " + imageSRC);
                            urlStream.close();
                        }
                        // try the reconnect one more time
                    }
                } else {
                    // unable to create cache file: read directly from the stream
                    Log.warn("Failed to create cache file " + tmpCacheFile);
                    imageSRC.readable = urlStream;
                    success = true;
                }
                
            } while (!success && tries < 2);

        } else if (imageSRC.readable instanceof java.io.File) {
            if (imageSRC.resource != null)
                setResourceMetaData(imageSRC.resource, (File) imageSRC.readable);
        }

        if (imageSRC.resource != null) // in case any held changes
            imageSRC.resource.getProperties().releaseChanges();

        final ImageInputStream inputStream;

        if (imageSRC.readable instanceof ImageInputStream)
            inputStream = (ImageInputStream) imageSRC.readable;
        else {
            //if (DEBUG.IMAGE) out("ImageIO converting " + tag(imageSRC.readable) + " to InputStream...");
            inputStream = ImageIO.createImageInputStream(imageSRC.readable);
        }

        if (DEBUG.IMAGE) out("Got ImageInputStream " + inputStream);

        if (inputStream == null)
            throw new ImageException("Can't Access"); // e,g., local file permission denied

        ImageReader reader = getDecoder(inputStream);

        if (reader == null) {
            badStream: {
                if (FirstFailure) {
                    // This FirstFailure code was an attempt to deal with what is now handled
                    // via DataException, but it's not a bad idea to keep it around.
                    FirstFailure = false;
                    Log.warn("No reader found: first failure, rescanning for codecs: " + imageSRC);
                    // TODO: okay, problem appears to be with the URLConnection / stream? Is only
                    // getting us tiny amount of bytes the first time...
                    if (DEBUG.Enabled) tufts.Util.printStackTrace("first failure: " + imageSRC);
                    ImageIO.scanForPlugins();
                    reader = getDecoder(inputStream);
                    if (reader != null)
                        break badStream;
                }
                if (DEBUG.IMAGE) out("NO IMAGE READER FOUND FOR " + imageSRC);
                throw new ImageException("Unreadable Image Stream");
            }
        }

        if (DEBUG.IMAGE) out("Chosen ImageReader for stream " + reader + " formatName=" + reader.getFormatName());
        
        //reader.addIIOReadProgressListener(new ReadListener());
        //out("added progress listener");

        reader.setInput(inputStream, false, true); // allow seek back, can ignore meta-data (can generate exceptions)
        if (DEBUG.IMAGE) out("Input for reader set to " + inputStream);
        if (DEBUG.IMAGE) out("Getting size...");
        int w = reader.getWidth(0);
        int h = reader.getHeight(0);
        if (DEBUG.IMAGE) out("ImageReader got size " + w + "x" + h);


        if (imageSRC.resource != null) {
            if (DEBUG.IMAGE || DEBUG.THREAD || DEBUG.RESOURCE)
                out("setting resource image.* meta-data for " + imageSRC.resource);
            
            imageSRC.resource.getProperties().holdChanges();
            imageSRC.resource.setProperty("image.width",  Integer.toString(w));
            imageSRC.resource.setProperty("image.height", Integer.toString(h));
            imageSRC.resource.setProperty("image.format", reader.getFormatName());
            imageSRC.resource.setCached(true);
            imageSRC.resource.getProperties().releaseChanges();
        }

        
        if (listener != null) {
            if (DEBUG.IMAGE) out("Sending size to " + tag(listener));
            listener.gotImageSize(imageSRC.original, w, h, dataSize);
        }

        // FYI, if fetch meta-data, will need to trap exceptions here, as if there are
        // any problems or inconsistencies with it, we'll get an exception, even if the
        // image is totally readable.
        //out("meta-data: " + reader.getImageMetadata(0));

        //-----------------------------------------------------------------------------
        // Now read the image, creating the BufferedImage
        // 
        // Todo performance: using Toolkit.getImage on MacOSX gets us OSXImages, instead
        // of the BufferedImages which we get from ImageIO, which are presumably
        // non-writeable, and may perform better / be cached at the OS level.  This of
        // course would only work for the original java image types: GIF, JPG, and PNG.
        //-----------------------------------------------------------------------------

        if (DEBUG.IMAGE) out("Reading " + reader);
        BufferedImage image = reader.read(0);
        if (DEBUG.IMAGE) out("ImageReader.read(0) got " + image);

        if (listener != null)
            listener.gotImage(imageSRC.original, image, w, h);

        inputStream.close();
        
         if (urlStream != null)
             urlStream.close();

        if (DEBUG.Enabled) {
            String[] tryProps = new String[] { "name", "title", "description", "comment" };
            for (int i = 0; i < tryProps.length; i++) {
                Object p = image.getProperty(tryProps[i], null);
                if (p != null && p != java.awt.Image.UndefinedProperty)
                    System.err.println("FOUND PROPERTY " + tryProps[i] + "=" + p);
            }
        }


        return image;
    }

    private static ImageReader getDecoder(ImageInputStream istream)
    {
        java.util.Iterator iri = ImageIO.getImageReaders(istream);

        ImageReader reader = null;
        int idx = 0;
        while (iri.hasNext()) {
            ImageReader ir = (ImageReader) iri.next();
            if (reader == null)
                reader = ir;
            if (DEBUG.IMAGE) out("\tfound ImageReader #" + idx + " " + ir);
            idx++;
        }

        return reader;
    }


    /* Apparently, not all decoders actually report to the listeners, (e.g., TIFF), so we're not using this for now */
    private static class ReadListener implements IIOReadProgressListener {
        public void sequenceStarted(ImageReader source, int minIndex) {
            out("sequenceStarted; minIndex="+minIndex);
        }
        public void sequenceComplete(ImageReader source) {
            out("sequenceComplete");
        }
        public void imageStarted(ImageReader source, int imageIndex) {
            out("imageStarted; imageIndex="+imageIndex);
        }
        public void imageProgress(ImageReader source, float pct) {
            out("imageProgress; "+(int)(pct + 0.5f) + "%");
        }
        public void imageComplete(ImageReader source) {
            out("imageComplete");
        }
        public void thumbnailStarted(ImageReader source, int imageIndex, int thumbnailIndex){}
        public void thumbnailProgress(ImageReader source, float percentageDone) {}
        public void thumbnailComplete(ImageReader source) {}
        public void readAborted(ImageReader source) {
            out("readAborted");
        }
    }

    private static String tag(Object o) {
        if (o instanceof java.awt.Component)
            return tufts.vue.gui.GUI.name(o);
        if (o instanceof LWComponent)
            return ((LWComponent)o).getDiagnosticLabel();
        
        String s = Util.tag(o);
        s += "[";
        if (o instanceof Thread) {
            s += ((Thread)o).getName();
        } else if (o instanceof BufferedImage) {
            BufferedImage bi = (BufferedImage) o;
            s += bi.getWidth() + "x" + bi.getHeight();
        } else if (o != null)
            s += o.toString();
        return s + "]";
    }
    
    private static void out(Object o) {
        Log.debug("Images " + (o==null?"null":o.toString()));

        /*
        String s = "Images " + (""+System.currentTimeMillis()).substring(8);
        s += " [" + Thread.currentThread().getName() + "]";
        System.err.println(s + " " + (o==null?"null":o.toString()));
        */
    }

    
    /*
    private static void copyStreamToFile(InputStream in, File file)
        throws java.io.IOException
    {
        ByteBuffer buf = ByteBuffer.allocate(2048);
        FileOutputStream fout = new FileOutputStream(file);
        FileChannel fcout = fout.getChannel();
        ReadableByteChannel chin = Channels.newChannel(in);

        while (true) { 
            buf.clear(); 
            int r = chin.read(buf);
            //out("read " + r + " bytes");
            if (DEBUG.IMAGE) System.err.print(r + "; ");
            if (r == -1)
                break; 
            buf.flip(); 
            fcout.write(buf);
        }

        fcout.close();
        chin.close();
        if (DEBUG.IMAGE) out("\nFILLED " + file);
    }

    
    private static File cacheURLContent(URL url, InputStream in)
        throws java.io.IOException
    {
        File file = getCacheFile(url);
        copyStreamToFile(in, file);
        return file;
    }
    */

    /*
    static {
         // ImageIO file caching is a runtime-only scheme for allowing
         // file streams to seek backwards: nothing to with a presistant store.
         // It's also on by default.
         javax.imageio.ImageIO.setUseCache(true);
         javax.imageio.ImageIO.setCacheDirectory(new java.io.File("/tmp"));
     }
    */

    public static void main(String args[]) throws Exception {

        // GUI init required for fully loading all image codecs (tiff gets left behind otherwise)
        // Ah: the TIFF reader in Java 1.5 apparently comes from the UI library:
        // [Loaded com.sun.imageio.plugins.tiff.TIFFImageReader
        // from /System/Library/Frameworks/JavaVM.framework/Versions/1.5.0/Classes/ui.jar]

        VUE.init(args);
        
        System.out.println(java.util.Arrays.asList(javax.imageio.ImageIO.getReaderFormatNames()));
        System.out.println(java.util.Arrays.asList(javax.imageio.ImageIO.getReaderMIMETypes()));

        String filename = args[0];
        java.io.File file = null;
        java.net.URL url = null;
        Object imageSRC;
        if (args[0].startsWith("http:") || args[0].startsWith("file:"))
            imageSRC = url = new java.net.URL(filename);
        else
            imageSRC = file = new java.io.File(filename);

        DEBUG.IMAGE=true;

        getImage(imageSRC, new LWImage());
        //loadImage(imageSRC, null);


        /*
          
        ImageInputStream iis = ImageIO.createImageInputStream(imageSRC);

        out("Got ImageInputStream " + iis);
        
        java.util.Iterator i = ImageIO.getImageReaders(iis);

        ImageReader IR = null;
        int idx = 0;
        while (i.hasNext()) {
            ImageReader ir = (ImageReader) i.next();
            if (IR == null)
                IR = ir;
            out("\tfound ImageReader #" + idx + " " + ir);
            idx++;
        }

        if (IR == null) {
            out("NO IMAGE READER FOUND FOR " + imageSRC);
            if (file == null)
                System.err.println("ImageIO.read got: " + ImageIO.read(url));
            else
                System.err.println("ImageIO.read got: " + ImageIO.read(file));
            //System.out.println("Reading " + file);
            System.exit(0);
        }

        out("Chosen ImageReader for stream " + IR + " formatName=" + IR.getFormatName());
        

        IR.addIIOReadProgressListener(new ReadListener());
        out("added progress listener");

        out("Reading " + IR);
        IR.setInput(iis);
        out("Input for reader set to " + iis);
        //out("meta-data: " + IR.getImageMetadata(0));
        out("Getting size...");
        int w = IR.getWidth(0);
        int h = IR.getHeight(0);
        out("ImageReader got size " + w + "x" + h);
        BufferedImage bi = IR.read(0);
        out("ImageReader.read(0) got " + bi);

        */




        /*
          The below code requires the JAI libraries:
          // JAI (Java Advandced Imaging) libraries
          /System/Library/Java/Extensions/jai_core.jar
          /System/Library/Java/Extensions/jai_codec.jar

          Using this code below will also get us decoding .fpx images,
          tho we would need to convert it from the resulting RenderedImage / PlanarImage
          
        */

        /*
        try {
            // Use the ImageCodec APIs
            com.sun.media.jai.codec.SeekableStream stream = new com.sun.media.jai.codec.FileSeekableStream(filename);
            String[] names = com.sun.media.jai.codec.ImageCodec.getDecoderNames(stream);
            System.out.println("ImageCodec API's found decoders: " + java.util.Arrays.asList(names));
            com.sun.media.jai.codec.ImageDecoder dec =
                com.sun.media.jai.codec.ImageCodec.createImageDecoder(names[0], stream, null);
            java.awt.image.RenderedImage im = dec.decodeAsRenderedImage();
            System.out.println("ImageCodec API's got RenderedImage: " + im);
            Object image = javax.media.jai.PlanarImage.wrapRenderedImage(im);
            System.out.println("ImageCodec API's got PlanarImage: " + image);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // We're not magically getting any new codec's added to ImageIO after the above code
        // finds the .fpx codec...
        
        System.out.println(java.util.Arrays.asList(javax.imageio.ImageIO.getReaderFormatNames()));
        System.out.println(java.util.Arrays.asList(javax.imageio.ImageIO.getReaderMIMETypes()));
        */


     }

}




/**
 * An implementation of <code>ImageInputStream</code> that gets its
 * input from a regular <code>InputStream</code>.  As the data
 * is read, is it backed by a File for seeking backward.
 *
 */
class FileBackedImageInputStream extends ImageInputStreamImpl
{
    private static final org.apache.log4j.Logger Log = org.apache.log4j.Logger.getLogger(FileBackedImageInputStream.class);

    private static final int BUFFER_LENGTH = 2048;

    private final RandomAccessFile cache;
    private final byte[] streamBuf = new byte[BUFFER_LENGTH];
    private final InputStream stream;
    
    private long length = 0L;
    private boolean foundEOF = false;

    private final Images.Listener listener;

    private final File file;

    // todo opt: pass in content size up front to init RAF to full size at start
    /**
     * @param stream - image data stream -- will be closed when reading is done
     * @param file - file to write incoming data to to use as cache
     */
    public FileBackedImageInputStream(InputStream stream, File file, Images.Listener listener)
        throws IOException, Images.ImageException
    {
        if (stream == null || file == null)
            throw new IllegalArgumentException("FileBackedImageInputStream: stream or file is null");

        this.stream = stream;
        this.cache = new RandomAccessFile(file, "rw");
        this.file = file;
        this.listener = listener;

        this.cache.setLength(0); // in case already there

        if (true) { 
            byte[] testBuf = new byte[16];
            read(testBuf);
            super.seek(0); // put as back at the start
            String content = new String(testBuf, "US-ASCII");
            
            if (DEBUG.IMAGE) {
                System.err.println("\n***CONTENT[" + content + "] streamPos=" + streamPos + " length=" + length);
            }

            // TODO: this readUntil is debug to get more info on the streams that are starting
            // with <HTML> every once in a while: we can inspect the cache file afterwords.
            readUntil(BUFFER_LENGTH);
        
            String test = content.toUpperCase();

            if (test.startsWith("<HTML>") || test.startsWith("<!DOCTYPE")) {
                Log.error("Stream " + stream + " contains HTML, not image data; [" + content + "]");
                close();
                throw new Images.DataException("Content is HTML, not image data");
            }
        }
    }

    /*
    public void seek(long pos) throws IOException {
        System.err.println("SEEK " + pos);
        super.seek(pos);
    }
    */

    /**
     * Ensures that at least <code>pos</code> bytes are cached,
     * or the end of the source is reached.  The return value
     * is equal to the smaller of <code>pos</code> and the
     * length of the source file.
     */
    private long readUntil(long pos) throws IOException {

        //System.err.println("<=" + pos + "; ");
        
        // We've already got enough data cached
        if (pos < length)
            return pos;

        // pos >= length but length isn't getting any bigger, so return it
        if (foundEOF)
            return length;

        long len = pos - length;
        cache.seek(length);
        while (len > 0) {
            // Copy a buffer's worth of data from the source to the cache
            // BUFFER_LENGTH will always fit into an int so this is safe
            final int nbytes = stream.read(streamBuf, 0, (int)Math.min(len, (long)BUFFER_LENGTH));
            if (nbytes == -1) {
                if (DEBUG.IMAGE && DEBUG.IO) System.err.println("<EOF @ " + length + ">");
                foundEOF = true;
                return length;
            }

            if (DEBUG.IMAGE && DEBUG.IO) System.err.print(">" + nbytes + "; ");
            cache.write(streamBuf, 0, nbytes);
            len -= nbytes;
            length += nbytes;
            //System.out.println("READ TO " + length);
            if (listener != null)
                listener.gotBytes(stream, length);
        }

        return pos;
    }

    public int read() throws IOException {
        bitOffset = 0;
        long next = streamPos + 1;
        long pos = readUntil(next);
        if (pos >= next) {
            if (DEBUG.IMAGE && DEBUG.IO) System.err.println("SEEK " + streamPos+1);
            cache.seek(streamPos++);
            return cache.read();
        } else {
            return -1;
        }
    }

    public int read(byte[] b, int off, int len) throws IOException {
        if (b == null)
            throw new NullPointerException();

        if (off < 0 || len < 0 || off + len > b.length || off + len < 0)
            throw new IndexOutOfBoundsException();

        if (len == 0)
            return 0;


        checkClosed();

        bitOffset = 0;

        long pos = readUntil(streamPos + len);

        // len will always fit into an int so this is safe
        len = (int)Math.min((long)len, pos - streamPos);
        if (len > 0) {
            if (DEBUG.IMAGE && DEBUG.IO) System.err.println("SEEK " + streamPos);
            cache.seek(streamPos);
            cache.readFully(b, off, len);
            streamPos += len;
            return len;
        } else {
            return -1;
        }
    }

    public void close() throws IOException {
        super.close();
        cache.close();
        stream.close();
    }

    public String toString() {
        return getClass().getName() + "[" + file.toString() + "]";
    }
}